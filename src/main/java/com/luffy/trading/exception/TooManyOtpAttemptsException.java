package com.luffy.trading.exception;

public class TooManyOtpAttemptsException extends RuntimeException {
    public TooManyOtpAttemptsException(String message) {
        super(message);
    }
}
