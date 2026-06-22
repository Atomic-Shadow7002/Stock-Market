package com.luffy.trading.exception;

/**
 * WHAT: Thrown when JwtService fails to parse/validate a token
 *       (malformed, bad signature, expired, unsupported).
 * WHY:  JJWT's library throws several different exception subtypes internally.
 *       Wrapping them into this one type means JwtFilter only needs to catch
 *       ONE exception to reject a request, and GlobalExceptionHandler only
 *       needs ONE handler to return a consistent 401 response.
 */
public class JwtValidationException extends RuntimeException {

    public JwtValidationException(String message) {
        super(message);
    }

    public JwtValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}