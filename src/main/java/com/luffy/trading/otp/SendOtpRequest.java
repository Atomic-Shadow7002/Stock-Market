package com.luffy.trading.otp;

import jakarta.validation.constraints.NotNull;

public record SendOtpRequest(

    @NotNull(message = "type is required (PHONE or EMAIL)")
    OtpType type

) {}
