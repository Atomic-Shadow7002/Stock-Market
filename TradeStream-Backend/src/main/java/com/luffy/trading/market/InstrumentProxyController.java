package com.luffy.trading.market;

import com.luffy.trading.angelone.AngelOneClient;
import com.luffy.trading.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * JWT-secured proxy for instrument lookup endpoints exposed by the Angel One service.
 *
 * These endpoints allow authenticated backend users to resolve trading symbols to
 * AngelOne instrument tokens, search for instruments by name/symbol, and check the
 * freshness of the instrument index. This is the "discovery" layer — users call these
 * before calling market-data endpoints when they don't already know the token.
 *
 * The Angel One service maintains a daily-refreshed snapshot of the full AngelOne
 * scrip master. All lookups are served from in-memory index — nothing here hits
 * AngelOne's live API directly (only the scheduled refresh does).
 *
 * NOTE on response shape: instrument endpoints return plain objects, NOT the
 * AngelEnvelope {status, message, errorcode, data}. We return the whole response
 * map as the data field in our ApiResponse<T>.
 */
@RestController
@RequestMapping("/instruments")
@RequiredArgsConstructor
public class InstrumentProxyController {

    private final AngelOneClient angelOneClient;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /instruments/resolve — look up a single instrument by symbol.
     *
     * Params:
     *  exchange — NSE | BSE | NFO | BFO | MCX | CDS
     *  symbol   — trading symbol e.g. SBIN-EQ, RELIANCE-EQ, NIFTY28AUG25FUT
     *
     * Returns: { found: true/false, instrument: { token, symbol, name, ... } }
     *
     * Example:
     *   GET /instruments/resolve?exchange=NSE&symbol=SBIN-EQ
     */
    @GetMapping("/resolve")
    public ResponseEntity<ApiResponse<Object>> resolve(
            @RequestParam String exchange,
            @RequestParam String symbol) {

        Map<String, Object> response = angelOneClient.get(
                "/instruments/resolve", Map.of("exchange", exchange, "symbol", symbol), MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Instrument resolved", response));
    }

    /**
     * GET /instruments/token — reverse lookup: token → instrument details.
     *
     * Params:
     *  exchange — NSE | BSE | NFO | BFO | MCX | CDS
     *  token    — AngelOne instrument token e.g. 3045
     *
     * Returns: { found: true/false, instrument: { token, symbol, name, ... } }
     *
     * Example:
     *   GET /instruments/token?exchange=NSE&token=3045
     */
    @GetMapping("/token")
    public ResponseEntity<ApiResponse<Object>> byToken(
            @RequestParam String exchange,
            @RequestParam String token) {

        Map<String, Object> response = angelOneClient.get(
                "/instruments/token", Map.of("exchange", exchange, "token", token), MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Instrument lookup complete", response));
    }

    /**
     * GET /instruments/search — search instruments by name or symbol prefix/substring.
     *
     * Params:
     *  query    — search term e.g. SBIN, RELIANCE, NIFTY
     *  exchange — (optional) filter by exchange; omit to search all exchanges
     *  limit    — max results (default 20, max 200)
     *
     * Results are ranked: prefix matches first, then contains matches.
     *
     * Example:
     *   GET /instruments/search?query=SBIN&exchange=NSE&limit=10
     *   GET /instruments/search?query=NIFTY
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Object>> search(
            @RequestParam String query,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false, defaultValue = "20") int limit) {

        Map<String, String> params = new HashMap<>();
        params.put("query", query);
        params.put("limit", String.valueOf(limit));
        if (exchange != null && !exchange.isBlank()) {
            params.put("exchange", exchange);
        }

        Map<String, Object> response = angelOneClient.get("/instruments/search", params, MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Instrument search complete", response));
    }

    /**
     * GET /instruments/status — health of the instrument index.
     *
     * Returns whether the instrument data is loaded, how many instruments are indexed,
     * when it was last refreshed, and whether it's considered stale (older than 30 hours).
     *
     * Example:
     *   GET /instruments/status
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Object>> status() {
        Map<String, Object> response = angelOneClient.get("/instruments/status", MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Instrument index status retrieved", response));
    }
}
