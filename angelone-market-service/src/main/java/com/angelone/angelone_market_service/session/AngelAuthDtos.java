package com.angelone.angelone_market_service.session;

/** Request/response shapes for AngelOne's auth endpoints, per SmartAPI docs. */
public class AngelAuthDtos {

    public record LoginRequest(String clientcode, String password, String totp) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record TokenData(String jwtToken, String refreshToken, String feedToken) {
    }

    public record AngelEnvelope<T>(boolean status, String message, String errorcode, T data) {
    }
}
