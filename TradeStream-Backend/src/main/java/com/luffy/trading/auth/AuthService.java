package com.luffy.trading.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.luffy.trading.exception.DuplicateResourceException;
import com.luffy.trading.exception.ResourceNotFoundException;
import com.luffy.trading.otp.OtpService;
import com.luffy.trading.otp.OtpType;
import com.luffy.trading.user.User;
import com.luffy.trading.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AuthRepository authRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    //  REGISTER
    @Transactional
    public AuthResponse register(RegisterRequest request) {

        var existingByPhone = userRepository.findByPhone(request.phone());

        // Phone already taken AND already verified → genuinely a duplicate,
        // someone else owns this number (or this same person already
        // finished onboarding) — reject as before.
        if (existingByPhone.isPresent() && existingByPhone.get().isPhoneVerified()) {
            throw new DuplicateResourceException("Phone number already registered");
        }

        if (StringUtils.hasText(request.email())) {
            var existingByEmail = userRepository.findByEmail(request.email());
            // Only block on email if it belongs to a *different*, already-
            // verified account. If it's this same unverified row (matched
            // above by phone), or another unverified abandoned row, let it
            // through below rather than dead-ending the user.
            if (existingByEmail.isPresent()
                    && existingByEmail.get().isEmailVerified()
                    && (existingByPhone.isEmpty()
                            || !existingByEmail.get().getId().equals(existingByPhone.get().getId()))) {
                throw new DuplicateResourceException("Email already registered");
            }
        }

        User user;
        if (existingByPhone.isPresent()) {
            // Unverified account from a previous, abandoned registration
            // attempt with this same phone. Resume it instead of creating
            // a duplicate row or permanently blocking this phone number:
            // refresh the profile fields/password in case they changed,
            // and re-send a fresh OTP.
            user = existingByPhone.get();
            user.setFirstName(request.firstName());
            user.setLastName(request.lastName());
            user.setEmail(StringUtils.hasText(request.email()) ? request.email() : null);
            user.setPassword(passwordEncoder.encode(request.password()));
            userRepository.save(user);
        } else {
            user = User.builder()
                    .firstName(request.firstName())
                    .lastName(request.lastName())
                    .phone(request.phone())
                    .email(StringUtils.hasText(request.email()) ? request.email() : null)
                    .password(passwordEncoder.encode(request.password()))
                    .phoneVerified(false)
                    .emailVerified(false)
                    .build();

            userRepository.save(user);
        }

        // Kick off phone verification immediately. The access token issued
        // below lets the client call /otp/verify right away, even though a
        // future /auth/login will be rejected until phoneVerified is true.
        otpService.generateAndSend(user, OtpType.PHONE);

        return issueTokenPair(user);
    }

    //  LOGIN
    @Transactional
    public AuthResponse login(LoginRequest request) {

        if (!StringUtils.hasText(request.phone()) && !StringUtils.hasText(request.email())) {
            throw new BadCredentialsException("Phone or email is required");
        }

        User user = StringUtils.hasText(request.phone())
                ? userRepository.findByPhone(request.phone())
                        .orElseThrow(() -> new BadCredentialsException("Invalid credentials"))
                : userRepository.findByEmail(request.email())
                        .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!user.isEnabled()) {
            throw new BadCredentialsException("Account is disabled");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        if (!user.isPhoneVerified()) {
            throw new BadCredentialsException(
                    "Phone number not verified — verify via /otp/verify before logging in");
        }

        return issueTokenPair(user);
    }

    //  REFRESH
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {

        RefreshToken stored = authRepository.findByToken(rawRefreshToken)
                .orElseThrow(() -> new ResourceNotFoundException("Refresh token not found"));

        if (stored.isExpired()) {
            authRepository.delete(stored);
            throw new BadCredentialsException("Refresh token has expired");
        }

        String newAccessToken = jwtService.generateAccessToken(stored.getUser().getId());
        return new AuthResponse(newAccessToken, rawRefreshToken);
    }

    //  LOGOUT
    @Transactional
    public void logout(String rawRefreshToken) {
        authRepository.findByToken(rawRefreshToken)
                .ifPresent(authRepository::delete);
    }

    //  INTERNAL
    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId());

        String rawRefreshToken = UUID.randomUUID().toString();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(rawRefreshToken)
                .expiresAt(OffsetDateTime.now()
                        .plusSeconds(jwtProperties.getRefreshTokenExpiry()))
                .createdAt(OffsetDateTime.now())
                .build();

        authRepository.save(refreshToken);

        return new AuthResponse(accessToken, rawRefreshToken);
    }
}