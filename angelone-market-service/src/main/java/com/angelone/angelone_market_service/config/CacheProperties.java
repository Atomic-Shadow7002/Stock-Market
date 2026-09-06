package com.angelone.angelone_market_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "cache")
public record CacheProperties(
        long quoteTtlMs,
        Map<String, Long> candleTtlMs, // keyed by AngelOne interval constant, e.g. "ONE_MINUTE"
        long greeksTtlMs,
        // Brokerage/margin results are a pure function of the request body (product,
        // qty, price, exchange, token) — not a live market snapshot — so it's safe to
        // cache these longer than quotes without ever serving a stale number.
        long brokerageTtlMs,
        long marginTtlMs,
        // Historical OI shares the exact same interval constants and "how fresh does
        // this need to be" reasoning as candleTtlMs — kept as its own map (rather than
        // reusing candleTtlMs) so OI retention can be tuned independently later without
        // touching candle behavior.
        Map<String, Long> oiTtlMs,
        // Exchange-published reference lists (which scrips allow intraday, at what
        // margin multiplier) change only when NSE/BSE issue a circular — not
        // tick-by-tick — so one TTL per exchange call is enough, no per-interval map.
        long intradayEligibleTtlMs,
        // ASM/GSM cautionary flags are exchange-driven surveillance actions, updated
        // periodically (not live-market-driven) — cache for a while, same reasoning.
        long cautionaryTtlMs
) {
}
