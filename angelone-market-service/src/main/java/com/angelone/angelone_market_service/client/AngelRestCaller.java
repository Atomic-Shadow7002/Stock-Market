package com.angelone.angelone_market_service.client;

import com.angelone.angelone_market_service.session.AngelSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * WHY the retry-once-after-refresh logic: our single session's JWT can occasionally go
 * stale between scheduled refreshes (e.g. AngelOne invalidated it server-side). Rather
 * than surface a raw 401/AG8002 to every caller, we force one refresh and retry — this
 * keeps all the "secure" marketdata endpoints resilient without duplicating this logic
 * in every controller.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AngelRestCaller {

    private final RestClient angelRestClient;
    private final AngelHeaders angelHeaders;
    private final AngelSessionManager sessionManager;

    public <TReq, TRes> TRes post(String path, TReq body, ParameterizedTypeReference<TRes> responseType) {
        try {
            return doPost(path, body, responseType);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                log.warn("AngelOne returned 401 on {} — forcing refresh and retrying once", path);
                sessionManager.refresh();
                return doPost(path, body, responseType);
            }
            throw e;
        }
    }

    public <TRes> TRes get(String path, ParameterizedTypeReference<TRes> responseType) {
        try {
            return doGet(path, responseType);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                log.warn("AngelOne returned 401 on {} — forcing refresh and retrying once", path);
                sessionManager.refresh();
                return doGet(path, responseType);
            }
            throw e;
        }
    }

    private <TReq, TRes> TRes doPost(String path, TReq body, ParameterizedTypeReference<TRes> responseType) {
        String jwt = sessionManager.currentTokens().jwtToken();
        return angelRestClient.post()
                .uri(path)
                .headers(h -> h.addAll(angelHeaders.forAuthenticated(jwt)))
                .body(body)
                .retrieve()
                .body(responseType);
    }

    private <TRes> TRes doGet(String path, ParameterizedTypeReference<TRes> responseType) {
        String jwt = sessionManager.currentTokens().jwtToken();
        return angelRestClient.get()
                .uri(path)
                .headers(h -> h.addAll(angelHeaders.forAuthenticated(jwt)))
                .retrieve()
                .body(responseType);
    }
}
