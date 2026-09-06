package com.luffy.trading.market;

import com.luffy.trading.angelone.AngelOneClient;
import com.luffy.trading.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin-only proxy for Angel One service management endpoints.
 *
 * WHY admin-only: these endpoints control or expose the internal state of the Angel
 * One service — re-logging in to AngelOne, forcing a fresh instrument dump, and
 * checking session status. Normal users have no business need for them, and an
 * accidental re-login call during market hours could briefly interrupt the live feed.
 *
 * Double-guarded same as AdminController: @PreAuthorize at method level AND the
 * /admin/** route guard in SecurityConfig. Two independent layers so a single
 * misconfiguration doesn't expose these endpoints.
 */
@RestController
@RequestMapping("/admin/angelone")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AngelOneAdminController {

    private final AngelOneClient angelOneClient;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    /**
     * GET /admin/angelone/session/status — is the Angel One service logged in?
     *
     * Returns { loggedIn: true/false }. Use this to quickly check whether the
     * service has an active AngelOne session before blaming market-data failures
     * on something else.
     */
    @GetMapping("/session/status")
    public ResponseEntity<ApiResponse<Object>> sessionStatus() {
        Map<String, Object> response = angelOneClient.get(
                "/internal/session/status", MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Angel One session status retrieved", response));
    }

    /**
     * POST /admin/angelone/session/relogin — force a fresh full login to AngelOne.
     *
     * Use this as a break-glass when the session is unexpectedly invalid (e.g.
     * AngelOne revoked the token server-side, or the scheduled refresh loop
     * failed multiple times and the feed is dark). The service uses the TOTP secret
     * from its own config — no credentials need to be passed here.
     *
     * This is synchronous: it returns only after the re-login completes (or fails).
     */
    @PostMapping("/session/relogin")
    public ResponseEntity<ApiResponse<Object>> relogin() {
        Map<String, Object> response = angelOneClient.post(
                "/internal/session/relogin", null, MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Angel One re-login triggered", response));
    }

    // -------------------------------------------------------------------------
    // Instrument management
    // -------------------------------------------------------------------------

    /**
     * POST /admin/angelone/instruments/refresh — force a fresh instrument dump from AngelOne.
     *
     * The Angel One service normally refreshes its instrument index every day at 08:30 IST.
     * Use this endpoint to force an immediate refresh outside that schedule — e.g. after an
     * AngelOne maintenance window, or if the status endpoint shows stale:true.
     *
     * This is synchronous and may take a few seconds (it fetches hundreds of thousands of
     * rows from AngelOne's scrip master endpoint). Returns the new index status on completion.
     */
    @PostMapping("/instruments/refresh")
    public ResponseEntity<ApiResponse<Object>> refreshInstruments() {
        Map<String, Object> response = angelOneClient.post(
                "/internal/instruments/refresh", null, MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Instrument index refreshed", response));
    }
}
