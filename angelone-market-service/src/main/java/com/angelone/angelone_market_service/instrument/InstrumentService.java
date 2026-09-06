package com.angelone.angelone_market_service.instrument;

import com.angelone.angelone_market_service.instrument.InstrumentDtos.Instrument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WHY there is exactly ONE of these for the whole app, same philosophy as
 * AngelSessionManager: AngelOne only exposes a single full-dump endpoint (no
 * per-symbol lookup, no delta/diff API), so there's one snapshot, refreshed
 * once a day, shared by every consumer (resolve/search callers, and eventually
 * every other market-data endpoint that currently requires callers to already
 * know the token).
 *
 * Lifecycle:
 *  - On startup: load whatever's on disk first (fast, works even if AngelOne's
 *    dump endpoint is unreachable), THEN attempt a live fetch to get today's data.
 *  - Daily cron: re-fetch the full dump, rebuild the index from scratch, swap it
 *    in atomically, persist it to disk. A full replace every time — AngelOne
 *    gives no smaller granularity than "the whole list," so partial/incremental
 *    updates aren't possible here.
 *  - On any failure (network down, bad JSON): keep serving the last good
 *    snapshot rather than clearing it — stale-but-correct beats empty.
 */
@Service
@Slf4j
public class InstrumentService {

    private final RestClient instrumentRestClient;
    private final InstrumentProperties props;
    private final ObjectMapper objectMapper;
    private final AtomicReference<InstrumentIndex> currentIndex = new AtomicReference<>(InstrumentIndex.empty());

    public InstrumentService(@Qualifier("instrumentRestClient") RestClient instrumentRestClient,
                              InstrumentProperties props, ObjectMapper objectMapper) {
        this.instrumentRestClient = instrumentRestClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ---- Lookups (read path — what every other consumer actually calls) ----

    public Instrument resolveBySymbol(String exchange, String symbol) {
        return currentIndex.get().resolveBySymbol(exchange, symbol);
    }

    public Instrument resolveByToken(String exchange, String token) {
        return currentIndex.get().resolveByToken(exchange, token);
    }

    public List<Instrument> search(String query, String exchangeOrNull, int limit) {
        return currentIndex.get().search(query, exchangeOrNull, Math.min(limit, 200));
    }

    /**
     * WHY this exists separately from resolveBySymbol: every other market-data endpoint
     * (quote/candles/brokerage/margin) needs a token or nothing — there's no sensible
     * "partial" result. Centralizing the not-found error message here means every caller
     * (MarketDataService) gets the same clear 400, not a silent null flowing into an
     * AngelOne request that then fails with a confusing upstream error instead.
     */
    public String requireToken(String exchange, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException(
                    "No token given and no symbol given either (exchange=" + exchange +
                    ") — provide one or the other");
        }
        Instrument instrument = resolveBySymbol(exchange, symbol);
        if (instrument == null) {
            throw new IllegalArgumentException(
                    "No instrument found for exchange=" + exchange + " symbol=" + symbol +
                    " — check spelling/exchange, or try GET /instruments/search?query=" + symbol);
        }
        return instrument.token();
    }

    public List<String> requireTokens(String exchange, List<String> symbols) {
        return symbols.stream().map(s -> requireToken(exchange, s)).toList();
    }

    /**
     * WHY "stale" is computed here rather than just exposing lastUpdated and making every
     * caller do their own math: this is the one signal that answers "should I be worried
     * / should I force a refresh" — every consumer of this status should agree on what
     * "too old" means, not reimplement the threshold check themselves.
     */
    public InstrumentDtos.InstrumentStatus status() {
        InstrumentIndex idx = currentIndex.get();
        boolean stale = idx.isLoaded()
                && java.time.Duration.between(idx.loadedAt(), java.time.Instant.now()).toHours() >= props.staleAfterHours();
        return new InstrumentDtos.InstrumentStatus(
                idx.isLoaded(),
                idx.size(),
                idx.source(),
                idx.isLoaded() ? idx.loadedAt().toString() : null,
                stale
        );
    }

    // ---- Loading (write path) ----

    /** Fast, local, non-network — called first on startup so we're never empty at boot. */
    public void loadFromDisk() {
        Path path = Path.of(props.cacheFilePath());
        if (!Files.exists(path)) {
            log.info("No instrument disk cache found at {} — will rely on live fetch", path);
            return;
        }
        try {
            List<Instrument> instruments = objectMapper.readValue(path.toFile(), new TypeReference<List<Instrument>>() {
            });
            currentIndex.set(InstrumentIndex.build(instruments, "disk"));
            log.info("Loaded {} instruments from disk cache ({})", instruments.size(), path);
        } catch (IOException e) {
            log.error("Failed to read instrument disk cache at {} — starting with no instrument data until live fetch succeeds", path, e);
        }
    }

    /** Full re-fetch from AngelOne's scrip master dump. Always a wholesale replace. */
    public synchronized void refreshFromAngelOne() {
        log.info("Fetching AngelOne instrument dump from {}", props.dumpUrl());
        List<Instrument> instruments = instrumentRestClient.get()
                .uri(props.dumpUrl())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Instrument>>() {
                });

        if (instruments == null || instruments.isEmpty()) {
            throw new IllegalStateException("AngelOne instrument dump returned empty — keeping previous snapshot");
        }

        currentIndex.set(InstrumentIndex.build(instruments, "live"));
        log.info("Instrument index refreshed: {} instruments loaded", instruments.size());

        writeToDisk(instruments);
    }

    private void writeToDisk(List<Instrument> instruments) {
        Path path = Path.of(props.cacheFilePath());
        try {
            File parent = path.toFile().getParentFile();
            if (parent != null) parent.mkdirs();
            objectMapper.writeValue(path.toFile(), instruments);
            log.info("Persisted instrument disk cache to {} ({} rows)", path, instruments.size());
        } catch (IOException e) {
            // Non-fatal: the in-memory index is still correct, we just won't survive
            // a restart with today's data until the next successful refresh.
            log.error("Failed to write instrument disk cache to {} — in-memory index is fine, but a restart before the next refresh will fall back to stale disk data", path, e);
        }
    }

    /** Daily re-fetch. AngelOne regenerates the dump once a day, so this matches that cadence. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${instrument.refresh-cron}")
    public void scheduledRefresh() {
        try {
            refreshFromAngelOne();
        } catch (Exception e) {
            log.error("Scheduled instrument refresh failed — continuing to serve the last good snapshot ({} instruments, loaded {})",
                    currentIndex.get().size(), currentIndex.get().loadedAt(), e);
        }
    }
}
