package com.luffy.trading.market;

import com.luffy.trading.angelone.AngelOneClient;
import com.luffy.trading.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT-secured proxy for all market-data endpoints exposed by the Angel One service.
 *
 * WHY this lives in the backend (not called directly): the backend owns all user
 * authentication. Every request here must carry a valid backend JWT — the Angel One
 * service never sees user credentials and doesn't need to. The backend validates the
 * JWT, then forwards the market-data request to the internal service using the shared
 * X-Internal-Api-Key (which the AngelOneClient injects automatically).
 *
 * Response shape: the Angel One service returns its own envelope
 * {status, message, errorcode, data}. We unwrap data and re-wrap it in the backend's
 * standard ApiResponse<T> so every endpoint in this application has a consistent shape.
 *
 * All endpoints are accessible to any authenticated user (ROLE_USER or ROLE_ADMIN).
 * Admin-only Angel One management endpoints live in AngelOneAdminController.
 */
@RestController
@RequestMapping("/market")
@RequiredArgsConstructor
public class MarketController {

    private final AngelOneClient angelOneClient;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    // -------------------------------------------------------------------------
    // Quote
    // -------------------------------------------------------------------------

    /**
     * GET /market/quote — real-time quote for one or more instruments.
     *
     * Params:
     *  mode     — LTP | FULL | OHLC
     *  exchange — NSE | BSE | NFO | BFO | MCX | CDS
     *  tokens   — comma-separated AngelOne instrument tokens (optional if symbols given)
     *  symbols  — comma-separated trading symbols e.g. SBIN-EQ,RELIANCE-EQ (optional if tokens given)
     *
     * At least one of tokens or symbols must be provided.
     *
     * Example:
     *   GET /market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ,RELIANCE-EQ
     *   GET /market/quote?mode=FULL&exchange=NSE&tokens=3045,2885
     */
    @GetMapping("/quote")
    public ResponseEntity<ApiResponse<Object>> quote(
            @RequestParam String mode,
            @RequestParam String exchange,
            @RequestParam(required = false) List<String> tokens,
            @RequestParam(required = false) List<String> symbols) {

        Map<String, String> params = new HashMap<>();
        params.put("mode", mode);
        params.put("exchange", exchange);
        if (tokens != null && !tokens.isEmpty()) {
            params.put("tokens", String.join(",", tokens));
        }
        if (symbols != null && !symbols.isEmpty()) {
            params.put("symbols", String.join(",", symbols));
        }

        Map<String, Object> response = angelOneClient.get("/market/quote", params, MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Quote data retrieved", response.get("data")));
    }

    // -------------------------------------------------------------------------
    // Candles (Historical OHLCV)
    // -------------------------------------------------------------------------

    /**
     * GET /market/candles — historical candlestick data.
     *
     * Params:
     *  exchange    — NSE | BSE | NFO | BFO | MCX | CDS
     *  symbolToken — AngelOne instrument token (optional if symbol given)
     *  symbol      — trading symbol e.g. SBIN-EQ (optional if symbolToken given)
     *  interval    — ONE_MINUTE | FIVE_MINUTE | FIFTEEN_MINUTE | THIRTY_MINUTE | ONE_HOUR | ONE_DAY
     *  fromDate    — yyyy-MM-dd HH:mm  e.g. 2026-07-01 09:15
     *  toDate      — yyyy-MM-dd HH:mm  e.g. 2026-08-01 15:30
     *
     * Example:
     *   GET /market/candles?exchange=NSE&symbol=SBIN-EQ&interval=ONE_DAY&fromDate=2026-07-01+09:15&toDate=2026-08-01+15:30
     */
    @GetMapping("/candles")
    public ResponseEntity<ApiResponse<Object>> candles(
            @RequestParam String exchange,
            @RequestParam(required = false) String symbolToken,
            @RequestParam(required = false) String symbol,
            @RequestParam String interval,
            @RequestParam String fromDate,
            @RequestParam String toDate) {

        Map<String, String> params = new HashMap<>();
        params.put("exchange", exchange);
        params.put("interval", interval);
        params.put("fromDate", fromDate);
        params.put("toDate", toDate);
        if (symbolToken != null && !symbolToken.isBlank()) params.put("symbolToken", symbolToken);
        if (symbol != null && !symbol.isBlank()) params.put("symbol", symbol);

        Map<String, Object> response = angelOneClient.get("/market/candles", params, MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Candle data retrieved", response.get("data")));
    }

    // -------------------------------------------------------------------------
    // Option Greeks
    // -------------------------------------------------------------------------

    /**
     * GET /market/greeks — option Greeks for a given underlying and expiry.
     *
     * Params:
     *  name       — underlying name e.g. NIFTY, BANKNIFTY, TCS
     *  expiryDate — AngelOne format e.g. 28AUG2025
     *
     * Example:
     *   GET /market/greeks?name=NIFTY&expiryDate=28AUG2025
     */
    @GetMapping("/greeks")
    public ResponseEntity<ApiResponse<Object>> greeks(
            @RequestParam String name,
            @RequestParam String expiryDate) {

        Map<String, Object> response = angelOneClient.get(
                "/market/greeks", Map.of("name", name, "expiryDate", expiryDate), MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Option Greeks retrieved", response.get("data")));
    }

    // -------------------------------------------------------------------------
    // Brokerage Calculator
    // -------------------------------------------------------------------------

    /**
     * POST /market/brokerage — estimate charges for a hypothetical order basket.
     * Does NOT place any order — pure calculation.
     *
     * Body:
     * {
     *   "orders": [
     *     {
     *       "productType": "DELIVERY",
     *       "transactionType": "BUY",
     *       "quantity": "10",
     *       "price": "800",
     *       "exchange": "NSE",
     *       "symbolName": "SBIN-EQ",
     *       "token": "3045"   ← optional; resolved from exchange+symbolName if omitted
     *     }
     *   ]
     * }
     */
    @PostMapping("/brokerage")
    public ResponseEntity<ApiResponse<Object>> brokerage(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = angelOneClient.post("/market/brokerage", body, MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Brokerage estimate retrieved", response.get("data")));
    }

    // -------------------------------------------------------------------------
    // Margin Calculator
    // -------------------------------------------------------------------------

    /**
     * POST /market/margin — real-time margin required for a basket of hypothetical positions.
     * Does NOT place any order — pure calculation. Up to 50 positions per request.
     *
     * Body:
     * {
     *   "positions": [
     *     {
     *       "exchange": "NFO",
     *       "qty": 50,
     *       "price": 0,
     *       "productType": "INTRADAY",
     *       "token": "67300",   ← optional; resolved from exchange+symbol if omitted
     *       "symbol": "NIFTY28AUG25FUT",
     *       "tradeType": "BUY",
     *       "orderType": "MARKET"
     *     }
     *   ]
     * }
     */
    @PostMapping("/margin")
    public ResponseEntity<ApiResponse<Object>> margin(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = angelOneClient.post("/market/margin", body, MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Margin calculation retrieved", response.get("data")));
    }

    // -------------------------------------------------------------------------
    // Historical Open Interest
    // -------------------------------------------------------------------------

    /**
     * GET /market/oi — historical open interest (F&O instruments only).
     *
     * Params:
     *  exchange    — NFO | BFO
     *  symbolToken — AngelOne instrument token (optional if symbol given)
     *  symbol      — trading symbol (optional if symbolToken given)
     *  interval    — ONE_MINUTE | THREE_MINUTE | FIVE_MINUTE | TEN_MINUTE | FIFTEEN_MINUTE |
     *                THIRTY_MINUTE | ONE_HOUR | ONE_DAY
     *  fromDate    — yyyy-MM-dd HH:mm
     *  toDate      — yyyy-MM-dd HH:mm
     *
     * Example:
     *   GET /market/oi?exchange=NFO&symbol=NIFTY28AUG25FUT&interval=THREE_MINUTE&fromDate=2026-08-06+11:15&toDate=2026-08-06+12:00
     */
    @GetMapping("/oi")
    public ResponseEntity<ApiResponse<Object>> oi(
            @RequestParam String exchange,
            @RequestParam(required = false) String symbolToken,
            @RequestParam(required = false) String symbol,
            @RequestParam String interval,
            @RequestParam String fromDate,
            @RequestParam String toDate) {

        Map<String, String> params = new HashMap<>();
        params.put("exchange", exchange);
        params.put("interval", interval);
        params.put("fromDate", fromDate);
        params.put("toDate", toDate);
        if (symbolToken != null && !symbolToken.isBlank()) params.put("symbolToken", symbolToken);
        if (symbol != null && !symbol.isBlank()) params.put("symbol", symbol);

        Map<String, Object> response = angelOneClient.get("/market/oi", params, MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Open interest data retrieved", response.get("data")));
    }

    // -------------------------------------------------------------------------
    // Intraday Eligible Scrips
    // -------------------------------------------------------------------------

    /**
     * GET /market/intraday-eligible — list of scrips currently eligible for intraday trading.
     * Cached for 6 hours by the Angel One service.
     *
     * Params:
     *  exchange — NSE | BSE
     *
     * Example:
     *   GET /market/intraday-eligible?exchange=NSE
     */
    @GetMapping("/intraday-eligible")
    public ResponseEntity<ApiResponse<Object>> intradayEligible(@RequestParam String exchange) {
        Map<String, Object> response = angelOneClient.get(
                "/market/intraday-eligible", Map.of("exchange", exchange), MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Intraday eligible scrips retrieved", response.get("data")));
    }

    // -------------------------------------------------------------------------
    // Cautionary Scrips (ASM/GSM)
    // -------------------------------------------------------------------------

    /**
     * GET /market/cautionary — current list of ASM/GSM caution-flagged scrips.
     * No parameters. Cached for 1 hour by the Angel One service.
     *
     * Example:
     *   GET /market/cautionary
     */
    @GetMapping("/cautionary")
    public ResponseEntity<ApiResponse<Object>> cautionary() {
        Map<String, Object> response = angelOneClient.get("/market/cautionary", MAP_TYPE);
        return ResponseEntity.ok(ApiResponse.success("Cautionary scrips retrieved", response.get("data")));
    }
}
