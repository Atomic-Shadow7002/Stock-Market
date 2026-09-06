package com.angelone.angelone_market_service.instrument;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WHY separate from AngelProperties: the scrip master dump is fetched from a different
 * host (margincalculator.angelone.in, not apiconnect.angelone.in) and needs no auth
 * headers at all — it's a plain public file, unlike every other AngelOne call this
 * service makes. Keeping its config isolated avoids implying it shares auth semantics
 * with the "secure" endpoints.
 */
@ConfigurationProperties(prefix = "instrument")
public record InstrumentProperties(
        String dumpUrl,
        String cacheFilePath,
        String refreshCron,
        // How old the loaded snapshot can get before GET /instruments/status starts
        // reporting stale:true. See InstrumentService.status() — this is the whole
        // "how do I know if I should force a refresh" signal for this data.
        long staleAfterHours
) {
}
