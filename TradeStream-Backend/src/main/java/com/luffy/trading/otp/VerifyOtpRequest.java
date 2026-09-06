package com.luffy.trading.otp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(

    @NotNull(message = "type is required (PHONE or EMAIL)")
    OtpType type,

    @NotBlank(message = "code is required")
    @Pattern(regexp = "^\\d{6}$", message = "code must be 6 digits")
    String code

) {}
