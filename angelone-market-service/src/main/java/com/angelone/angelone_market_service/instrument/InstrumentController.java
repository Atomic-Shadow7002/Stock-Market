package com.angelone.angelone_market_service.instrument;

import com.angelone.angelone_market_service.instrument.InstrumentDtos.Instrument;
import com.angelone.angelone_market_service.instrument.InstrumentDtos.InstrumentStatus;
import com.angelone.angelone_market_service.instrument.InstrumentDtos.ResolveResponse;
import com.angelone.angelone_market_service.instrument.InstrumentDtos.SearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Guarded by InternalApiKeyFilter same as every other REST controller here — this is
 * the missing piece every other /market/** endpoint implicitly assumed existed
 * somewhere: turning a human-readable symbol into the token AngelOne actually wants.
 */
@RestController
@RequiredArgsConstructor
public class InstrumentController {

    private final InstrumentService instrumentService;

    /** GET /instruments/resolve?exchange=NSE&symbol=SBIN-EQ -> the matching instrument (incl. token) */
    @GetMapping("/instruments/resolve")
    public ResolveResponse resolve(@RequestParam String exchange, @RequestParam String symbol) {
        Instrument instrument = instrumentService.resolveBySymbol(exchange, symbol);
        return new ResolveResponse(instrument != null, instrument);
    }

    /** GET /instruments/token?exchange=NSE&token=3045 -> the matching instrument (reverse lookup) */
    @GetMapping("/instruments/token")
    public ResolveResponse byToken(@RequestParam String exchange, @RequestParam String token) {
        Instrument instrument = instrumentService.resolveByToken(exchange, token);
        return new ResolveResponse(instrument != null, instrument);
    }

    /**
     * GET /instruments/search?query=SBIN&exchange=NSE&limit=20
     * exchange is optional (omit to search across all exchanges). limit defaults to 20, capped at 200.
     */
    @GetMapping("/instruments/search")
    public SearchResponse search(@RequestParam String query,
                                  @RequestParam(required = false) String exchange,
                                  @RequestParam(required = false, defaultValue = "20") int limit) {
        List<Instrument> results = instrumentService.search(query, exchange, limit);
        return new SearchResponse(results.size(), results);
    }

    /** GET /instruments/status -> is data loaded, how many rows, from where, how fresh */
    @GetMapping("/instruments/status")
    public InstrumentStatus status() {
        return instrumentService.status();
    }

    /** POST /internal/instruments/refresh — break-glass manual refresh, same pattern as /internal/session/relogin */
    @PostMapping("/internal/instruments/refresh")
    public InstrumentStatus refresh() {
        instrumentService.refreshFromAngelOne();
        return instrumentService.status();
    }
}
