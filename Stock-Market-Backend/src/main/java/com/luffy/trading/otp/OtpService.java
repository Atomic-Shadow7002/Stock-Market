package com.luffy.trading.otp;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.luffy.trading.config.RateLimitService;
import com.luffy.trading.exception.InvalidOtpException;
import com.luffy.trading.exception.OtpExpiredException;
import com.luffy.trading.exception.ResourceNotFoundException;
import com.luffy.trading.exception.TooManyOtpAttemptsException;
import com.luffy.trading.user.User;
import com.luffy.trading.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OtpService {

    private static final int OTP_LENGTH = 6;
    private static final int EXPIRY_MINUTES = 5;
    static final int MAX_ATTEMPTS = 5;

    private final OtpRepository otpRepository;
    private final UserRepository userRepository;
    private final OtpSender otpSender;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public void generateAndSend(User user, OtpType type) {
        rateLimitService.checkOtpSendPerUser(user.getId());

        String plainCode = generateCode();
        String hash = passwordEncoder.encode(plainCode);

        // "One active OTP per user per type": wipe any existing one first.
        otpRepository.deleteByUserIdAndType(user.getId(), type);

        OtpVerification otp = OtpVerification.builder()
                .user(user)
                .type(type)
                .codeHash(hash)
                .expiresAt(OffsetDateTime.now().plusMinutes(EXPIRY_MINUTES))
                .attempts(0)
                .createdAt(OffsetDateTime.now())
                .build();

        otpRepository.save(otp);

        otpSender.send(user, type, plainCode);
    }

    /**
     * Verifies a submitted code. On success, deletes the OTP row immediately
     * (single use) and flips the relevant verification flag on the user.
     * On failure, increments the attempt counter; reaching MAX_ATTEMPTS
     * invalidates (deletes) the OTP so the user must request a new one.
     */
    @Transactional
    public void verify(User user, OtpType type, String submittedCode) {
        OtpVerification otp = otpRepository.findByUserIdAndType(user.getId(), type)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active " + type + " OTP found — request a new one"));

        if (otp.isExpired()) {
            otpRepository.delete(otp);
            throw new OtpExpiredException(type + " OTP has expired — request a new one");
        }

        if (otp.getAttempts() >= MAX_ATTEMPTS) {
            otpRepository.delete(otp);
            throw new TooManyOtpAttemptsException(
                    "Maximum verification attempts exceeded — OTP invalidated, request a new one");
        }

        boolean matches = passwordEncoder.matches(submittedCode, otp.getCodeHash());

        if (!matches) {
            otp.setAttempts(otp.getAttempts() + 1);
            if (otp.getAttempts() >= MAX_ATTEMPTS) {
                otpRepository.delete(otp);
                throw new TooManyOtpAttemptsException(
                        "Maximum verification attempts exceeded — OTP invalidated, request a new one");
            }
            otpRepository.save(otp);
            throw new InvalidOtpException("Incorrect code");
        }

        // Success — single-use, delete immediately.
        otpRepository.delete(otp);

        // PHONE/EMAIL codes prove contact-detail ownership, so they flip the
        // matching verification flag. PASSWORD_RESET codes prove "you still
        // hold this phone right now" for a security-sensitive action — they
        // deliberately do NOT touch phoneVerified/emailVerified. The caller
        // (UserService.changePassword) is responsible for what happens next.
        switch (type) {
            case PHONE -> user.setPhoneVerified(true);
            case EMAIL -> user.setEmailVerified(true);
            case PASSWORD_RESET -> { /* no verification flag to flip */ }
        }
        userRepository.save(user);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, OTP_LENGTH);
        int code = secureRandom.nextInt(bound);
        return String.format("%0" + OTP_LENGTH + "d", code);
    }
}
