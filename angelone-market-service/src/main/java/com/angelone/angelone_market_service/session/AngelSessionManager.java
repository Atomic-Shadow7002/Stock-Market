package com.angelone.angelone_market_service.session;

import com.angelone.angelone_market_service.client.AngelHeaders;
import com.angelone.angelone_market_service.config.AngelProperties;
import com.angelone.angelone_market_service.session.AngelAuthDtos.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicReference;

/**
 * WHY this is the center of the whole service: there is exactly ONE AngelOne session
 * for the entire application, shared by every downstream consumer. This class is the
 * single source of truth for "are we logged in, and what's the current token."
 *
 * Lifecycle (per AngelOne docs):
 *  - A session is valid until 12 midnight regardless of activity -> forced daily re-login.
 *  - The JWT access token itself is shorter-lived -> proactive refresh via generateTokens,
 *    well before it'd naturally expire, so live requests never hit a 401 mid-flight.
 *
 * Anything else in this service (feed client, REST proxying) reads the current token
 * from here — nothing else talks to loginByPassword/generateTokens directly.
 */
@Component
@Slf4j
public class AngelSessionManager {

    private final RestClient restClient;
    private final AngelProperties props;
    private final AngelHeaders angelHeaders;
    private final TotpGenerator totpGenerator;

    // AtomicReference because the WebSocket client + REST controllers read this
    // concurrently while the scheduled refresh thread may be writing it.
    private final AtomicReference<TokenData> currentTokens = new AtomicReference<>();

    private volatile Runnable onSessionRenewed = () -> {
    };

    public AngelSessionManager(@Qualifier("angelRestClient") RestClient angelRestClient, AngelProperties props,
                                AngelHeaders angelHeaders, TotpGenerator totpGenerator) {
        this.restClient = angelRestClient;
        this.props = props;
        this.angelHeaders = angelHeaders;
        this.totpGenerator = totpGenerator;
    }

    /** Called by the feed client to know when to reconnect with a fresh feedToken. */
    public void setOnSessionRenewed(Runnable callback) {
        this.onSessionRenewed = callback;
    }

    public TokenData currentTokens() {
        TokenData tokens = currentTokens.get();
        if (tokens == null) {
            throw new IllegalStateException("AngelOne session not established yet — login has not completed");
        }
        return tokens;
    }

    public boolean isLoggedIn() {
        return currentTokens.get() != null;
    }

    /** Full fresh login using clientcode + pin + live TOTP. Run at startup and daily. */
    public synchronized void login() {
        log.info("Logging into AngelOne as client {}", props.clientCode());
        String code = totpGenerator.currentCode(props.totpSecret());

        LoginRequest body = new LoginRequest(props.clientCode(), props.pin(), code);
        HttpEntity<LoginRequest> entity = new HttpEntity<>(body, angelHeaders.forLogin());

        AngelEnvelope<TokenData> response = restClient.post()
                .uri("/rest/auth/angelbroking/user/v1/loginByPassword")
                .headers(h -> h.addAll(entity.getHeaders()))
                .body(entity.getBody())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<AngelEnvelope<TokenData>>() {
                });

        applyAndNotify(response, "login");
    }

    /** Cheaper than a full login — exchanges the refresh token for a new jwt/feed token pair. */
    public synchronized void refresh() {
        TokenData existing = currentTokens.get();
        if (existing == null) {
            log.warn("No existing session to refresh — falling back to full login");
            login();
            return;
        }

        RefreshRequest body = new RefreshRequest(existing.refreshToken());
        HttpEntity<RefreshRequest> entity = new HttpEntity<>(body, angelHeaders.forAuthenticated(existing.jwtToken()));

        AngelEnvelope<TokenData> response = restClient.post()
                .uri("/rest/auth/angelbroking/jwt/v1/generateTokens")
                .headers(h -> h.addAll(entity.getHeaders()))
                .body(entity.getBody())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<AngelEnvelope<TokenData>>() {
                });

        applyAndNotify(response, "refresh");
    }

    private void applyAndNotify(AngelEnvelope<TokenData> response, String action) {
        if (response == null || !response.status() || response.data() == null) {
            String msg = response != null ? response.message() : "null response";
            log.error("AngelOne {} failed: {}", action, msg);
            throw new IllegalStateException("AngelOne " + action + " failed: " + msg);
        }
        currentTokens.set(response.data());
        log.info("AngelOne {} succeeded", action);
        onSessionRenewed.run();
    }

    /** Forced daily re-login — session hard-expires at midnight regardless of activity. */
    @Scheduled(cron = "${angel.daily-relogin-cron}")
    public void dailyRelogin() {
        try {
            login();
        } catch (Exception e) {
            log.error("Scheduled daily AngelOne login failed — will retry on next cron tick", e);
        }
    }

    /** Proactive refresh so live requests never race a token expiry. */
    @Scheduled(fixedDelayString = "${angel.token-refresh-interval-ms}", initialDelayString = "${angel.token-refresh-interval-ms}")
    public void scheduledRefresh() {
        if (!isLoggedIn()) return; // startup login handles the first token
        try {
            refresh();
        } catch (Exception e) {
            log.error("Scheduled AngelOne token refresh failed — attempting full login instead", e);
            try {
                login();
            } catch (Exception inner) {
                log.error("Fallback login also failed — feed will be stale until next cycle", inner);
            }
        }
    }
}
