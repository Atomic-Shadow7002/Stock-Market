package com.luffy.trading.user;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String firstName,
    String lastName,
    String phone,
    String email,           // nullable
    String role,
    boolean enabled,
    boolean phoneVerified,
    boolean emailVerified,
    OffsetDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getEmail(),
                user.getRole().name(),
                user.getEnabled(),
                user.isPhoneVerified(),
                user.isEmailVerified(),
                user.getCreatedAt()
        );
    }
}
