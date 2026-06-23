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

    //  REGISTER
    @Transactional
    public AuthResponse register(RegisterRequest request) {

        // Uniqueness checks — explicit messages so the client knows which field conflicts
        if (userRepository.existsByPhone(request.phone())) {
            throw new DuplicateResourceException("Phone number already registered");
        }
        if (StringUtils.hasText(request.email()) &&
                userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already registered");
        }

        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .phone(request.phone())
                .email(StringUtils.hasText(request.email()) ? request.email() : null)
                .password(passwordEncoder.encode(request.password()))
                .build();

        userRepository.save(user);

        return issueTokenPair(user);
    }

    //  LOGIN
    @Transactional
    public AuthResponse login(LoginRequest request) {

        // At least one identifier must be present
        if (!StringUtils.hasText(request.phone()) && !StringUtils.hasText(request.email())) {
            throw new BadCredentialsException("Phone or email is required");
        }

        // Phone takes priority per spec
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

        return issueTokenPair(user);
    }

    //  REFRESH
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {

        RefreshToken stored = authRepository.findByToken(rawRefreshToken)
                .orElseThrow(() -> new ResourceNotFoundException("Refresh token not found"));

        if (stored.isExpired()) {
            authRepository.delete(stored); // clean up expired token
            throw new BadCredentialsException("Refresh token has expired");
        }

        // Issue new access token only — refresh token stays unchanged
        String newAccessToken = jwtService.generateAccessToken(stored.getUser().getId());
        return new AuthResponse(newAccessToken, rawRefreshToken);
    }

    //  LOGOUT
    @Transactional
    public void logout(String rawRefreshToken) {
        // Silently succeed if token not found — idempotent logout
        authRepository.findByToken(rawRefreshToken)
                .ifPresent(authRepository::delete);
    }

    //  INTERNAL
    /**
     * Generates an access token + persists a new refresh token, returns both.
     * Called by both register and login — single place to change token strategy.
     */
    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId());

        String rawRefreshToken = UUID.randomUUID().toString(); // opaque random token

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