package com.luffy.trading.auth;

public record AuthResponse(
    String accessToken,
    String refreshToken
) {}