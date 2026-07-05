package com.luffy.trading.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.luffy.trading.auth.AuthRepository;
import com.luffy.trading.config.RateLimitService;
import com.luffy.trading.exception.DuplicateResourceException;
import com.luffy.trading.exception.ResourceNotFoundException;
import com.luffy.trading.otp.OtpService;
import com.luffy.trading.otp.OtpType;
import com.luffy.trading.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AuthRepository authRepository;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;

    @Transactional(readOnly = true)
    public UserResponse getOwnProfile() {
        return UserResponse.from(currentUser());
    }

    /**
     * WHAT: Updates firstName/lastName/email.
     * WHY emailVerified is untouched here (by design decision, not an
     * oversight): if the new email collides with someone else's *verified*
     * account, that's a genuine conflict and gets rejected. Otherwise the
     * email is simply swapped in — no re-verification is triggered, and
     * emailVerified keeps whatever value it already had.
     */
    @Transactional
    public UserResponse updateOwnProfile(UpdateProfileRequest request) {
        User user = currentUser();

        String newEmail = StringUtils.hasText(request.email()) ? request.email() : null;
        if (newEmail != null && !newEmail.equalsIgnoreCase(user.getEmail())) {
            userRepository.findByEmail(newEmail).ifPresent(other -> {
                if (other.isEmailVerified() && !other.getId().equals(user.getId())) {
                    throw new DuplicateResourceException("Email already registered");
                }
            });
        }

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(newEmail);
        userRepository.save(user);

        return UserResponse.from(user);
    }

    /**
     * WHAT: Step 1 of changing a password — fires a PASSWORD_RESET OTP to
     * the caller's own phone.
     * WHY clientIp is a parameter: OtpService.generateAndSend() already
     * enforces the per-user OTP-send limit internally, but the per-IP limit
     * (5/hour) lives in OtpController for the PHONE/EMAIL send path — this
     * mirrors that same check here so password-reset OTPs share the same
     * IP-level ceiling instead of quietly having none.
     */
    @Transactional
    public void requestPasswordChangeOtp(String clientIp) {
        rateLimitService.checkOtpSendPerIp(clientIp);
        otpService.generateAndSend(currentUser(), OtpType.PASSWORD_RESET);
    }

    /**
     * WHAT: Step 2 — verifies the code and, only on success, updates the
     * password. On success, also revokes every refresh token the user
     * currently holds: a password change is a "kick everyone else off"
     * moment, standard practice so a stolen refresh token stops working
     * the instant the legitimate owner changes their password.
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = currentUser();

        // Throws InvalidOtpException / OtpExpiredException /
        // TooManyOtpAttemptsException on failure — caught by
        // GlobalExceptionHandler, nothing more to do here on that path.
        otpService.verify(user, OtpType.PASSWORD_RESET, request.code());

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        authRepository.deleteAllByUserId(user.getId());
    }

    private User currentUser() {
        return userRepository.findById(SecurityUtils.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
