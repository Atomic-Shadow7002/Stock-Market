package com.luffy.trading.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(

    @NotBlank(message = "First name is required")
    String firstName,

    @NotBlank(message = "Last name is required")
    String lastName,

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone must be E.164 format (+919876543210)")
    String phone,

    @NotBlank(message = "Password is required")
    String password,

    @Email(message = "Email must be valid")
    String email    // optional — null is fine, empty string is not

) {}