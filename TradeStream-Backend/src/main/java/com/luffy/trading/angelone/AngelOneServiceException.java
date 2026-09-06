package com.luffy.trading.angelone;

/**
 * Thrown when the Angel One market-data service is unreachable, returns an
 * unexpected error status, or the response indicates an upstream AngelOne failure.
 *
 * WHY a dedicated exception instead of re-throwing the generic RestClientException:
 * the GlobalExceptionHandler maps this to a clean 502/503 ApiResponse with a user-
 * facing message, rather than leaking raw Spring HTTP-client stack traces. It also
 * lets us distinguish "the internal service is down" from other kinds of server error.
 */
public class AngelOneServiceException extends RuntimeException {

    private final int upstreamStatus;

    public AngelOneServiceException(String message, int upstreamStatus) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public AngelOneServiceException(String message, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = 503;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}
