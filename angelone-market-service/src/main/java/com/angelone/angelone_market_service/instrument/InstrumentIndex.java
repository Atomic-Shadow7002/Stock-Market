package com.angelone.angelone_market_service.instrument;

import com.angelone.angelone_market_service.instrument.InstrumentDtos.Instrument;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WHY this whole object is rebuilt from scratch on every refresh rather than mutated:
 * AngelOne only ever gives you the FULL dump, never a diff — there's no per-row "this
 * changed" signal. So the only correct move each day is "throw away yesterday's index,
 * build a brand new one from today's full list, then atomically swap it in." Any
 * in-place merge would risk keeping stale rows for delisted/expired instruments that
 * silently dropped out of today's dump.
 *
 * Three views over the same underlying list:
 *  - bySymbolKey: exact "exchange|SYMBOL" -> Instrument, for resolve-by-symbol
 *  - byTokenKey:  exact "exchange|token"  -> Instrument, for resolve-by-token
 *  - all: flat list with precomputed uppercase symbol/name, for search (avoids
 *    re-uppercasing on every search request)
 */
public final class InstrumentIndex {

    public record Searchable(Instrument instrument, String upperSymbol, String upperName) {
    }

    private final Map<String, Instrument> bySymbolKey;
    private final Map<String, Instrument> byTokenKey;
    private final List<Searchable> all;
    private final Instant loadedAt;
    private final String source;

    private InstrumentIndex(Map<String, Instrument> bySymbolKey, Map<String, Instrument> byTokenKey,
                             List<Searchable> all, Instant loadedAt, String source) {
        this.bySymbolKey = bySymbolKey;
        this.byTokenKey = byTokenKey;
        this.all = all;
        this.loadedAt = loadedAt;
        this.source = source;
    }

    public static InstrumentIndex build(List<Instrument> instruments, String source) {
        Map<String, Instrument> bySymbolKey = new HashMap<>(instruments.size() * 2);
        Map<String, Instrument> byTokenKey = new HashMap<>(instruments.size() * 2);
        List<Searchable> all = new ArrayList<>(instruments.size());

        for (Instrument i : instruments) {
            if (i.exchSeg() == null || i.symbol() == null || i.token() == null) continue;

            String exch = i.exchSeg().toUpperCase();
            bySymbolKey.put(exch + "|" + i.symbol().toUpperCase(), i);
            byTokenKey.put(exch + "|" + i.token(), i);
            all.add(new Searchable(
                    i,
                    i.symbol().toUpperCase(),
                    i.name() == null ? "" : i.name().toUpperCase()
            ));
        }

        return new InstrumentIndex(
                Collections.unmodifiableMap(bySymbolKey),
                Collections.unmodifiableMap(byTokenKey),
                Collections.unmodifiableList(all),
                Instant.now(),
                source
        );
    }

    public static InstrumentIndex empty() {
        return new InstrumentIndex(Map.of(), Map.of(), List.of(), null, "none");
    }

    public Instrument resolveBySymbol(String exchange, String symbol) {
        return bySymbolKey.get(exchange.toUpperCase() + "|" + symbol.toUpperCase());
    }

    public Instrument resolveByToken(String exchange, String token) {
        return byTokenKey.get(exchange.toUpperCase() + "|" + token);
    }

    /**
     * Prefix-first, then contains, case-insensitive, across symbol and name.
     * Linear scan over `all` — fine at this scale (tens of thousands of rows, infrequent
     * search calls) without needing a trie/sorted-prefix structure.
     */
    public List<Instrument> search(String query, String exchangeOrNull, int limit) {
        String q = query.toUpperCase();
        String exch = exchangeOrNull == null ? null : exchangeOrNull.toUpperCase();

        List<Instrument> prefixMatches = new ArrayList<>();
        List<Instrument> containsMatches = new ArrayList<>();

        for (Searchable s : all) {
            if (exch != null && !s.instrument().exchSeg().toUpperCase().equals(exch)) continue;

            boolean symbolPrefix = s.upperSymbol().startsWith(q);
            boolean namePrefix = s.upperName().startsWith(q);
            if (symbolPrefix || namePrefix) {
                prefixMatches.add(s.instrument());
            } else if (s.upperSymbol().contains(q) || s.upperName().contains(q)) {
                containsMatches.add(s.instrument());
            }
            if (prefixMatches.size() >= limit) break;
        }

        List<Instrument> merged = new ArrayList<>(prefixMatches);
        for (Instrument i : containsMatches) {
            if (merged.size() >= limit) break;
            merged.add(i);
        }
        return merged.size() > limit ? merged.subList(0, limit) : merged;
    }

    public int size() {
        return byTokenKey.size();
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public String source() {
        return source;
    }

    public boolean isLoaded() {
        return loadedAt != null;
    }
}
