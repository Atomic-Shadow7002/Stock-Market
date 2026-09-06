package com.angelone.angelone_market_service.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WHY manual Caffeine beans instead of Spring's @Cacheable: TTL varies per candle
 * interval (a 1-min candle should expire far sooner than a 1-day candle), which a
 * single annotation-level TTL can't express. candleCache uses Caffeine's variable
 * Expiry so each entry carries its own TTL via CandleCacheEntry.ttlMillis.
 */
@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, Object> quoteCache(CacheProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(props.quoteTtlMs()))
                .maximumSize(5000)
                .build();
    }

    @Bean
    public Cache<String, CandleCacheEntry> candleCache() {
        return Caffeine.newBuilder()
                .expireAfter(new Expiry<String, CandleCacheEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CandleCacheEntry value, long currentTime) {
                        return TimeUnit.MILLISECONDS.toNanos(value.ttlMillis());
                    }

                    @Override
                    public long expireAfterUpdate(String key, CandleCacheEntry value, long currentTime, long currentDuration) {
                        return TimeUnit.MILLISECONDS.toNanos(value.ttlMillis());
                    }

                    @Override
                    public long expireAfterRead(String key, CandleCacheEntry value, long currentTime, long currentDuration) {
                        return currentDuration; // reads don't extend TTL
                    }
                })
                .maximumSize(5000)
                .build();
    }

    /**
     * Same variable-TTL-per-key shape as candleCache (see CandleCacheEntry) — historical
     * OI shares the exact same AngelOne interval constants (ONE_MINUTE..ONE_DAY), so a
     * 1-minute OI series should expire far sooner than a 1-day one, same reasoning as candles.
     * Kept as a genuinely separate bean (not a reused candleCache instance) so OI and price
     * candles never collide on cache keys and can have independently-tuned TTLs.
     */
    @Bean
    public Cache<String, CandleCacheEntry> oiCache() {
        return Caffeine.newBuilder()
                .expireAfter(new Expiry<String, CandleCacheEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CandleCacheEntry value, long currentTime) {
                        return TimeUnit.MILLISECONDS.toNanos(value.ttlMillis());
                    }

                    @Override
                    public long expireAfterUpdate(String key, CandleCacheEntry value, long currentTime, long currentDuration) {
                        return TimeUnit.MILLISECONDS.toNanos(value.ttlMillis());
                    }

                    @Override
                    public long expireAfterRead(String key, CandleCacheEntry value, long currentTime, long currentDuration) {
                        return currentDuration; // reads don't extend TTL
                    }
                })
                .maximumSize(2000)
                .build();
    }

    /**
     * Tiny cache (at most 2 real entries — "NSE" and "BSE") for the intraday-eligible
     * scrip lists. A flat expireAfterWrite is fine here — unlike candles/OI, there's no
     * per-request "interval" to vary the TTL by; the whole list refreshes as one unit
     * whenever NSE/BSE issue a new circular.
     */
    @Bean
    public Cache<String, Object> intradayEligibleCache(CacheProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(props.intradayEligibleTtlMs()))
                .maximumSize(10)
                .build();
    }

    /** Same reasoning as intradayEligibleCache — one logical entry, the full cautionary list. */
    @Bean
    public Cache<String, Object> cautionaryCache(CacheProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(props.cautionaryTtlMs()))
                .maximumSize(10)
                .build();
    }

    @Bean
    public Cache<String, Object> greeksCache(CacheProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(props.greeksTtlMs()))
                .maximumSize(2000)
                .build();
    }

    @Bean
    public Cache<String, Object> brokerageCache(CacheProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(props.brokerageTtlMs()))
                .maximumSize(2000)
                .build();
    }

    @Bean
    public Cache<String, Object> marginCache(CacheProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(props.marginTtlMs()))
                .maximumSize(2000)
                .build();
    }

    /** Wraps a cached candle response together with the TTL it should live for. */
    public record CandleCacheEntry(Object payload, long ttlMillis) {
    }
}
