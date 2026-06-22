package com.luffy.trading.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

    String phone,   // optional — phone OR email must be present (validated in service)
    String email,   // optional

    @NotBlank(message = "Password is required")
    String password

) {}