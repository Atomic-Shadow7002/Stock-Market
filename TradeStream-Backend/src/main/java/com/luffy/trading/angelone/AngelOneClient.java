package com.luffy.trading.angelone;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Thin HTTP client for all backend → Angel One service calls.
 *
 * WHY this abstraction exists:
 *  - Base URL + X-Internal-Api-Key are handled once (in AngelOneConfig) and applied
 *    to every request automatically.
 *  - Connection failures (service down, network timeout) are caught here and mapped
 *    to AngelOneServiceException → 503 Service Unavailable.
 *  - 4xx errors from the Angel One service are passed through (the service already
 *    validated the request). 5xx errors map to 502 Bad Gateway.
 *
 * URI construction strategy — WHY we use the UriBuilder lambda, not URI objects:
 *   RestClient.get().uri(URI uri) stores the URI object as-is and does NOT combine it
 *   with the configured baseUrl. RestClient.get().uri(Function<UriBuilder, URI>) receives
 *   a UriBuilder that already has the baseUrl (http://localhost:8081) pre-set; any path
 *   appended via b.path("/market/quote") correctly resolves to the full URL. This is
 *   Spring RestClient's documented approach for relative URI resolution.
 */
@Component
@Slf4j
public class AngelOneClient {

    private final RestClient restClient;

    public AngelOneClient(@Qualifier("angelOneRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * GET with query parameters.
     * Uses the UriBuilder lambda so the configured baseUrl is properly applied.
     * Null and blank parameter values are silently skipped.
     */
    public <T> T get(String path, Map<String, String> params, ParameterizedTypeReference<T> responseType) {
        try {
            return restClient.get()
                    .uri(b -> {
                        b.path(path);
                        if (params != null) {
                            params.forEach((k, v) -> {
                                if (v != null && !v.isBlank()) {
                                    b.queryParam(k, v);
                                }
                            });
                        }
                        return b.build();
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 4xx: the Angel One service rejected the request parameters.
                        // Pass through the status code so the caller sees the real error.
                        throw new AngelOneServiceException(
                                "Angel One service returned " + resp.getStatusCode().value(),
                                resp.getStatusCode().value());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.error("Angel One service 5xx on GET {}: {}", path, resp.getStatusCode());
                        throw new AngelOneServiceException(
                                "Angel One service error: " + resp.getStatusCode().value(),
                                resp.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (ResourceAccessException ex) {
            log.error("Angel One service unreachable on GET {}: {}", path, ex.getMessage());
            throw new AngelOneServiceException(
                    "Angel One market service is currently unavailable — please try again shortly", ex);
        } catch (AngelOneServiceException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            log.error("Angel One service error on GET {}: {}", path, ex.getStatusCode());
            throw new AngelOneServiceException(
                    "Angel One service error: " + ex.getStatusCode().value(), ex.getStatusCode().value());
        }
    }

    /**
     * GET with no query parameters. Delegates to get(path, params, responseType) with null params.
     */
    public <T> T get(String path, ParameterizedTypeReference<T> responseType) {
        return get(path, null, responseType);
    }

    /**
     * POST with an optional body. When body is null, sends a POST with no request body
     * (used for trigger-style endpoints like relogin and instrument refresh).
     */
    public <TReq, TRes> TRes post(String path, @Nullable TReq body,
                                   ParameterizedTypeReference<TRes> responseType) {
        try {
            // body(T) throws NPE when given null in some Spring versions, so branch explicitly.
            var retrieveSpec = (body != null)
                    ? restClient.post().uri(path).body(body).retrieve()
                    : restClient.post().uri(path).retrieve();

            return retrieveSpec
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        throw new AngelOneServiceException(
                                "Angel One service returned " + resp.getStatusCode().value(),
                                resp.getStatusCode().value());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.error("Angel One service 5xx on POST {}: {}", path, resp.getStatusCode());
                        throw new AngelOneServiceException(
                                "Angel One service error: " + resp.getStatusCode().value(),
                                resp.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (ResourceAccessException ex) {
            log.error("Angel One service unreachable on POST {}: {}", path, ex.getMessage());
            throw new AngelOneServiceException(
                    "Angel One market service is currently unavailable — please try again shortly", ex);
        } catch (AngelOneServiceException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            log.error("Angel One service error on POST {}: {}", path, ex.getStatusCode());
            throw new AngelOneServiceException(
                    "Angel One service error: " + ex.getStatusCode().value(), ex.getStatusCode().value());
        }
    }
}
