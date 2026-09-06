package com.angelone.angelone_market_service.marketdata;

import com.github.benmanes.caffeine.cache.Cache;
import com.angelone.angelone_market_service.client.AngelRestCaller;
import com.angelone.angelone_market_service.config.CacheConfig.CandleCacheEntry;
import com.angelone.angelone_market_service.config.CacheProperties;
import com.angelone.angelone_market_service.instrument.InstrumentService;
import com.angelone.angelone_market_service.marketdata.MarketDataDtos.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * WHY caching lives here rather than at the controller: AngelOne's per-second rate
 * limits (see SmartAPI docs, e.g. 3 req/sec for candle data, 10 req/sec for quotes)
 * apply to our ONE account regardless of how many website visitors are asking. If
 * 50 people load a chart for the same symbol within the same second, they should get
 * one shared upstream call, not 50.
 *
 * WHY InstrumentService is injected here too: every method below used to require the
 * caller to already know AngelOne's numeric token — that's the "hardcoded" gap. Now
 * every method accepts a human-readable symbol as an alternative, and resolves it to
 * a token via the shared instrument index before ever building the AngelOne request.
 * Explicit token, when given, always wins — resolution only kicks in when it's absent.
 */
@Service
public class MarketDataService {

    private final AngelRestCaller restCaller;
    private final CacheProperties cacheProperties;
    private final InstrumentService instrumentService;
    private final Cache<String, Object> quoteCache;
    private final Cache<String, CandleCacheEntry> candleCache;
    private final Cache<String, Object> greeksCache;
    private final Cache<String, Object> brokerageCache;
    private final Cache<String, Object> marginCache;
    private final Cache<String, CandleCacheEntry> oiCache;
    private final Cache<String, Object> intradayEligibleCache;
    private final Cache<String, Object> cautionaryCache;

    private static final java.util.Set<String> INTRADAY_EXCHANGES = java.util.Set.of("NSE", "BSE");

    // Explicit constructor (not Lombok) because several caches share the exact same
    // generic type (Cache<String, Object> / Cache<String, CandleCacheEntry>) —
    // @Qualifier on a constructor parameter is the reliable way to disambiguate;
    // relying on Lombok to copy it isn't guaranteed.
    public MarketDataService(AngelRestCaller restCaller,
                              CacheProperties cacheProperties,
                              InstrumentService instrumentService,
                              @Qualifier("quoteCache") Cache<String, Object> quoteCache,
                              @Qualifier("candleCache") Cache<String, CandleCacheEntry> candleCache,
                              @Qualifier("greeksCache") Cache<String, Object> greeksCache,
                              @Qualifier("brokerageCache") Cache<String, Object> brokerageCache,
                              @Qualifier("marginCache") Cache<String, Object> marginCache,
                              @Qualifier("oiCache") Cache<String, CandleCacheEntry> oiCache,
                              @Qualifier("intradayEligibleCache") Cache<String, Object> intradayEligibleCache,
                              @Qualifier("cautionaryCache") Cache<String, Object> cautionaryCache) {
        this.restCaller = restCaller;
        this.cacheProperties = cacheProperties;
        this.instrumentService = instrumentService;
        this.quoteCache = quoteCache;
        this.candleCache = candleCache;
        this.greeksCache = greeksCache;
        this.brokerageCache = brokerageCache;
        this.marginCache = marginCache;
        this.oiCache = oiCache;
        this.intradayEligibleCache = intradayEligibleCache;
        this.cautionaryCache = cautionaryCache;
    }

    /**
     * GET /market/quote entry point. Exactly one of tokens/symbols must resolve to a
     * non-empty list — tokens wins if both are given (no ambiguity-guessing).
     */
    public AngelEnvelope<Object> getQuote(String mode, String exchange, List<String> tokens, List<String> symbols) {
        List<String> resolvedTokens;
        if (tokens != null && !tokens.isEmpty()) {
            resolvedTokens = tokens;
        } else if (symbols != null && !symbols.isEmpty()) {
            resolvedTokens = instrumentService.requireTokens(exchange, symbols);
        } else {
            throw new IllegalArgumentException("Provide either 'tokens' or 'symbols' (with 'exchange')");
        }
        return getQuote(mode, Map.of(exchange, resolvedTokens));
    }

    @SuppressWarnings("unchecked")
    public AngelEnvelope<Object> getQuote(String mode, Map<String, java.util.List<String>> exchangeTokens) {
        String key = "quote:" + mode + ":" + exchangeTokens;
        Object cached = quoteCache.getIfPresent(key);
        if (cached != null) return (AngelEnvelope<Object>) cached;

        AngelEnvelope<Object> response = restCaller.post(
                "/rest/secure/angelbroking/market/v1/quote/",
                new QuoteRequest(mode, exchangeTokens),
                new ParameterizedTypeReference<AngelEnvelope<Object>>() {
                });
        quoteCache.put(key, response);
        return response;
    }

    /**
     * GET /market/candles entry point. `symbolToken` wins if given; otherwise `symbol`
     * (with `exchange`) is resolved to a token first.
     */
    public AngelEnvelope<Object> getCandles(String exchange, String symbolToken, String symbol,
                                             String interval, String fromDate, String toDate) {
        String token = (symbolToken != null && !symbolToken.isBlank())
                ? symbolToken
                : instrumentService.requireToken(exchange, symbol);
        return getCandles(exchange, token, interval, fromDate, toDate);
    }

    @SuppressWarnings("unchecked")
    public AngelEnvelope<Object> getCandles(String exchange, String symbolToken, String interval,
                                             String fromDate, String toDate) {
        String key = "candle:" + exchange + ":" + symbolToken + ":" + interval + ":" + fromDate + ":" + toDate;
        CandleCacheEntry cached = candleCache.getIfPresent(key);
        if (cached != null) return (AngelEnvelope<Object>) cached.payload();

        AngelEnvelope<Object> response = restCaller.post(
                "/rest/secure/angelbroking/historical/v1/getCandleData",
                new CandleRequest(exchange, symbolToken, interval, fromDate, toDate),
                new ParameterizedTypeReference<AngelEnvelope<Object>>() {
                });

        long ttl = cacheProperties.candleTtlMs().getOrDefault(interval, 60_000L);
        candleCache.put(key, new CandleCacheEntry(response, ttl));
        return response;
    }

    @SuppressWarnings("unchecked")
    public AngelEnvelope<Object> getGreeks(String underlyingName, String expiryDate) {
        String key = "greeks:" + underlyingName + ":" + expiryDate;
        Object cached = greeksCache.getIfPresent(key);
        if (cached != null) return (AngelEnvelope<Object>) cached;

        AngelEnvelope<Object> response = restCaller.post(
                "/rest/secure/angelbroking/marketData/v1/optionGreek",
                new GreeksRequest(underlyingName, expiryDate),
                new ParameterizedTypeReference<AngelEnvelope<Object>>() {
                });
        greeksCache.put(key, response);
        return response;
    }

    /**
     * WHY this is safe to cache like everything else here even though it's a "calculator",
     * not a live quote: the result is a pure function of the order list in the request body
     * (product/txn type, qty, price, exchange, token) — nothing about it changes between two
     * identical requests a few seconds apart, unlike a quote's ltp. Caching still matters
     * because this is the ONE shared AngelOne account, so if your site shows an order-preview
     * "estimated charges" figure to many visitors modeling the same trade, they shouldn't each
     * cost a separate upstream call.
     *
     * Each order's `token` is resolved from `exchange`+`symbolName` when omitted — cache key
     * is built AFTER resolution so two requests that differ only in "gave token explicitly"
     * vs "let it resolve to the same token" correctly share one cache entry.
     */
    @SuppressWarnings("unchecked")
    public AngelEnvelope<Object> getBrokerage(java.util.List<BrokerageOrder> orders) {
        List<BrokerageOrder> resolved = orders.stream().map(this::resolveBrokerageToken).toList();

        String key = "brokerage:" + resolved;
        Object cached = brokerageCache.getIfPresent(key);
        if (cached != null) return (AngelEnvelope<Object>) cached;

        AngelEnvelope<Object> response = restCaller.post(
                "/rest/secure/angelbroking/brokerage/v1/estimateCharges",
                new BrokerageRequest(resolved),
                new ParameterizedTypeReference<AngelEnvelope<Object>>() {
                });
        brokerageCache.put(key, response);
        return response;
    }

    private BrokerageOrder resolveBrokerageToken(BrokerageOrder order) {
        if (order.token() != null && !order.token().isBlank()) return order;
        String token = instrumentService.requireToken(order.exchange(), order.symbolName());
        return new BrokerageOrder(order.productType(), order.transactionType(), order.quantity(),
                order.price(), order.exchange(), order.symbolName(), token);
    }

    /**
     * Same reasoning as getBrokerage: margin required for a given basket of hypothetical
     * positions is deterministic given the inputs, so it's cacheable like everything else.
     * Each position's `token` is resolved from `exchange`+`symbol` when omitted; the `symbol`
     * field itself is dropped before forwarding since AngelOne's margin API doesn't know it —
     * see MarginPosition vs MarginPositionInput in MarketDataDtos.
     */
    public AngelEnvelope<Object> getMargin(java.util.List<MarginPositionInput> positions) {
        List<MarginPosition> resolved = positions.stream().map(this::resolveMarginPosition).toList();
        return getMarginResolved(resolved);
    }

    private MarginPosition resolveMarginPosition(MarginPositionInput input) {
        String token = (input.token() != null && !input.token().isBlank())
                ? input.token()
                : instrumentService.requireToken(input.exchange(), input.symbol());
        return new MarginPosition(input.exchange(), input.qty(), input.price(), input.productType(),
                token, input.tradeType(), input.orderType());
    }

    @SuppressWarnings("unchecked")
    private AngelEnvelope<Object> getMarginResolved(java.util.List<MarginPosition> positions) {
        String key = "margin:" + positions;
        Object cached = marginCache.getIfPresent(key);
        if (cached != null) return (AngelEnvelope<Object>) cached;

        AngelEnvelope<Object> response = restCaller.post(
                "/rest/secure/angelbroking/margin/v1/batch",
                new MarginRequest(positions),
                new ParameterizedTypeReference<AngelEnvelope<Object>>() {
                });
        marginCache.put(key, response);
        return response;
    }

    /**
     * GET /market/oi entry point — historical open interest, sibling of /market/candles
     * (same interval constants, same max-days-per-request limits per AngelOne's docs).
     * `symbolToken` wins if given; otherwise `symbol` (with `exchange`) is resolved first.
     * Only meaningful for F&O instruments (NFO/BFO) — OI doesn't exist for cash-market
     * equities/indices, so resolving a cash-market symbol here will still succeed at the
     * token-lookup stage but AngelOne itself will return empty/erroring data — that's
     * AngelOne's own domain rule, not something this service validates ahead of time.
     */
    public AngelEnvelope<Object> getOi(String exchange, String symbolToken, String symbol,
                                        String interval, String fromDate, String toDate) {
        String token = (symbolToken != null && !symbolToken.isBlank())
                ? symbolToken
                : instrumentService.requireToken(exchange, symbol);
        return getOiResolved(exchange, token, interval, fromDate, toDate);
    }

    @SuppressWarnings("unchecked")
    private AngelEnvelope<Object> getOiResolved(String exchange, String symbolToken, String interval,
                                                 String fromDate, String toDate) {
        String key = "oi:" + exchange + ":" + symbolToken + ":" + interval + ":" + fromDate + ":" + toDate;
        CandleCacheEntry cached = oiCache.getIfPresent(key);
        if (cached != null) return (AngelEnvelope<Object>) cached.payload();

        AngelEnvelope<Object> response = restCaller.post(
                "/rest/secure/angelbroking/historical/v1/getOIData",
                new OiRequest(exchange, symbolToken, interval, fromDate, toDate),
                new ParameterizedTypeReference<AngelEnvelope<Object>>() {
                });

        long ttl = cacheProperties.oiTtlMs().getOrDefault(interval, 60_000L);
        oiCache.put(key, new CandleCacheEntry(response, ttl));
        return response;
    }

    /**
     * GET /market/intraday-eligible entry point. `exchange` must be NSE or BSE — AngelOne
     * exposes these as two separate GET endpoints (nseIntraday / bseIntraday) with an
     * identical response shape, so this collapses them into one parameterized call rather
     * than forcing callers to know two different paths for what's conceptually one lookup.
     * No token resolution needed — this isn't a per-symbol call, it returns the whole
     * exchange's current eligible list plus each scrip's margin multiplier.
     */
    @SuppressWarnings("unchecked")
    public AngelEnvelope<Object> getIntradayEligible(String exchange) {
        String ex = exchange == null ? null : exchange.trim().toUpperCase();
        if (!INTRADAY_EXCHANGES.contains(ex)) {
            throw new IllegalArgumentException(
                    "exchange must be NSE or BSE for /market/intraday-eligible, got: " + exchange);
        }

        String key = "intraday-eligible:" + ex;
        Object cached = intradayEligibleCache.getIfPresent(key);
        if (cached != null) return (AngelEnvelope<Object>) cached;

        String path = ex.equals("NSE")
                ? "/rest/secure/angelbroking/marketData/v1/nseIntraday"
                : "/rest/secure/angelbroking/marketData/v1/bseIntraday";
        AngelEnvelope<Object> response = restCaller.get(path, new ParameterizedTypeReference<AngelEnvelope<Object>>() {
        });
        intradayEligibleCache.put(key, response);
        return response;
    }

    /**
     * GET /market/cautionary entry point. No params on AngelOne's side either — it's
     * always the single, full, current list of ASM/GSM caution-flagged scrips across
     * exchanges. AngelOne's own docs show a request body ({"scripconsent":"yes"}) next to
     * a GET-with-no-body code sample for this endpoint; the code sample is authoritative
     * here (GET requests conventionally carry no body), so this is sent as a bodyless GET
     * like every other AngelOne GET in this service.
     */
    @SuppressWarnings("unchecked")
    public AngelEnvelope<Object> getCautionaryScrips() {
        String key = "cautionary";
        Object cached = cautionaryCache.getIfPresent(key);
        if (cached != null) return (AngelEnvelope<Object>) cached;

        AngelEnvelope<Object> response = restCaller.get(
                "/rest/secure/angelbroking/securities/v1/cautionaryScrips",
                new ParameterizedTypeReference<AngelEnvelope<Object>>() {
                });
        cautionaryCache.put(key, response);
        return response;
    }
}
