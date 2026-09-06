package com.angelone.angelone_market_service.instrument;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class InstrumentDtos {

    /**
     * Mirrors one row of AngelOne's OpenAPIScripMaster.json dump exactly. That file uses
     * lower_snake-ish keys (exch_seg, tick_size) that Jackson can't map from camelCase
     * automatically, hence the explicit @JsonProperty on those two.
     */
    public record Instrument(
            String token,
            String symbol,
            String name,
            String expiry,
            String strike,
            String lotsize,
            String instrumenttype,
            @JsonProperty("exch_seg") String exchSeg,
            @JsonProperty("tick_size") String tickSize
    ) {
    }

    /** GET /instruments/resolve and GET /instruments/token response shape. */
    public record ResolveResponse(boolean found, Instrument instrument) {
    }

    /** GET /instruments/search response shape. */
    public record SearchResponse(int count, List<Instrument> results) {
    }

    /** GET /instruments/status and POST /internal/instruments/refresh response shape. */
    public record InstrumentStatus(
            boolean loaded,
            int count,
            String source,       // "disk" or "live"
            String lastUpdated,  // ISO-8601, null if never loaded
            // true once the loaded snapshot is older than instrument.stale-after-hours
            // (default 30h — one missed daily cron cycle plus a buffer). This is the
            // signal to act on: page/alert on it, or just POST /internal/instruments/refresh
            // yourself. It does NOT mean the data is wrong — AngelOne's dump is still
            // whatever it was last time this service could reach it — only that it's
            // old enough that a refresh has apparently been missed.
            boolean stale
    ) {
    }
}
