package com.luffy.trading.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank(message = "code is required")
    @Pattern(regexp = "^\\d{6}$", message = "code must be 6 digits")
    String code,

    @NotBlank(message = "newPassword is required")
    @Size(min = 8, message = "newPassword must be at least 8 characters")
    String newPassword
) {}
