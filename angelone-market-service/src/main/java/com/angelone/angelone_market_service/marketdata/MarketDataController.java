package com.angelone.angelone_market_service.marketdata;

import com.angelone.angelone_market_service.marketdata.MarketDataDtos.AngelEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Everything here is called by YOUR trading backend (or any other internal service),
 * guarded by InternalApiKeyFilter — never directly by a browser. Response shapes mirror
 * AngelOne's own envelope ({status, message, errorcode, data}) so you can reuse whatever
 * DTOs/parsing you may already be planning on the trading-backend side.
 *
 * Every endpoint below accepts a raw AngelOne token OR a human-readable symbol — never
 * both required. Symbols are resolved to tokens via InstrumentService (backed by the
 * daily-refreshed scrip master, see the instrument package) rather than needing to be
 * known/hardcoded by the caller ahead of time. If you don't have a token, look one up
 * via GET /instruments/resolve or /instruments/search first, or just pass the symbol
 * directly to these endpoints and let them resolve it for you.
 */
@RestController
@RequestMapping("/market")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;

    /**
     * Example (by token, original way): GET /market/quote?mode=FULL&exchange=NSE&tokens=3045,881
     * Example (by symbol, no token needed): GET /market/quote?mode=FULL&exchange=NSE&symbols=SBIN-EQ,RELIANCE-EQ
     * If both are given, tokens wins. Exactly one of the two must resolve to something —
     * see MarketDataService.getQuote for the exact validation error otherwise.
     * (kept as simple query params for one exchange at a time; extend to a POST body
     * if you need to mix multiple exchanges in a single call, mirroring AngelOne's shape)
     */
    @GetMapping("/quote")
    public AngelEnvelope<Object> quote(@RequestParam String mode,
                                        @RequestParam String exchange,
                                        @RequestParam(required = false) List<String> tokens,
                                        @RequestParam(required = false) List<String> symbols) {
        return marketDataService.getQuote(mode, exchange, tokens, symbols);
    }

    /**
     * Example (by token, original way): GET /market/candles?exchange=NSE&symbolToken=3045&interval=ONE_DAY
     *              &fromDate=2026-07-01 09:15&toDate=2026-08-01 15:30
     * Example (by symbol, no token needed): GET /market/candles?exchange=NSE&symbol=SBIN-EQ&interval=ONE_DAY
     *              &fromDate=2026-07-01 09:15&toDate=2026-08-01 15:30
     * If both symbolToken and symbol are given, symbolToken wins.
     */
    @GetMapping("/candles")
    public AngelEnvelope<Object> candles(@RequestParam String exchange,
                                          @RequestParam(required = false) String symbolToken,
                                          @RequestParam(required = false) String symbol,
                                          @RequestParam String interval,
                                          @RequestParam String fromDate,
                                          @RequestParam String toDate) {
        return marketDataService.getCandles(exchange, symbolToken, symbol, interval, fromDate, toDate);
    }

    /**
     * Example: GET /market/greeks?name=TCS&expiryDate=25JAN2024
     * No token resolution here — AngelOne's own optionGreek API takes the underlying's
     * plain name directly, there was never a hardcoded-token problem on this endpoint.
     */
    @GetMapping("/greeks")
    public AngelEnvelope<Object> greeks(@RequestParam String name, @RequestParam String expiryDate) {
        return marketDataService.getGreeks(name, expiryDate);
    }

    /**
     * POST /market/brokerage — estimate brokerage/taxes/charges for a hypothetical order
     * (or a basket of them). POST because it's a list of orders, not a handful of scalars
     * that fit cleanly in query params like the GET endpoints above.
     *
     * `token` is now OPTIONAL per order — if omitted, it's resolved from `exchange`+`symbolName`.
     * Body (token known):   {"orders": [{"productType":"DELIVERY","transactionType":"BUY","quantity":"10",
     *                                    "price":"800","exchange":"NSE","symbolName":"SBIN-EQ","token":"3045"}]}
     * Body (token unknown): {"orders": [{"productType":"DELIVERY","transactionType":"BUY","quantity":"10",
     *                                    "price":"800","exchange":"NSE","symbolName":"SBIN-EQ"}]}
     *
     * This never places the order — it only asks AngelOne what it WOULD cost.
     */
    @PostMapping("/brokerage")
    public AngelEnvelope<Object> brokerage(@RequestBody MarketDataDtos.BrokerageRequest request) {
        return marketDataService.getBrokerage(request.orders());
    }

    /**
     * POST /market/margin — real-time margin required for a basket of up to 50 hypothetical
     * positions (per AngelOne's docs). Also never places anything — pure "what-if" calculation.
     *
     * `token` is now OPTIONAL per position — if omitted, it's resolved from `exchange`+`symbol`.
     * Body (token known):   {"positions": [{"exchange":"NFO","qty":50,"price":0,"productType":"INTRADAY",
     *                                        "token":"67300","tradeType":"BUY","orderType":"MARKET"}]}
     * Body (token unknown): {"positions": [{"exchange":"NFO","qty":50,"price":0,"productType":"INTRADAY",
     *                                        "symbol":"NIFTY28AUG25FUT","tradeType":"BUY","orderType":"MARKET"}]}
     * orderType defaults to "LIMIT" per AngelOne's docs if omitted — pass it explicitly to
     * avoid relying on that default.
     */
    @PostMapping("/margin")
    public AngelEnvelope<Object> margin(@RequestBody MarketDataDtos.MarginRequestInput request) {
        return marketDataService.getMargin(request.positions());
    }

    /**
     * Example (by token): GET /market/oi?exchange=NFO&symbolToken=46823&interval=THREE_MINUTE
     *              &fromDate=2026-08-06 11:15&toDate=2026-08-06 12:00
     * Example (by symbol): GET /market/oi?exchange=NFO&symbol=NIFTY28AUG25FUT&interval=THREE_MINUTE
     *              &fromDate=2026-08-06 11:15&toDate=2026-08-06 12:00
     * Historical open interest — sibling of /market/candles: same date format
     * (yyyy-MM-dd hh:mm), same interval constants, same max-days-per-request caps per
     * AngelOne's docs. If both symbolToken and symbol are given, symbolToken wins.
     * Only meaningful for F&O contracts (NFO/BFO) — OI is not a cash-market concept.
     */
    @GetMapping("/oi")
    public AngelEnvelope<Object> oi(@RequestParam String exchange,
                                     @RequestParam(required = false) String symbolToken,
                                     @RequestParam(required = false) String symbol,
                                     @RequestParam String interval,
                                     @RequestParam String fromDate,
                                     @RequestParam String toDate) {
        return marketDataService.getOi(exchange, symbolToken, symbol, interval, fromDate, toDate);
    }

    /**
     * Example: GET /market/intraday-eligible?exchange=NSE  (or exchange=BSE)
     * Which scrips AngelOne currently allows for intraday trading on that exchange, and
     * each one's margin multiplier. Pure reference data — no token/symbol resolution
     * involved, since this isn't a per-symbol call, it's the whole eligible list at once.
     */
    @GetMapping("/intraday-eligible")
    public AngelEnvelope<Object> intradayEligible(@RequestParam String exchange) {
        return marketDataService.getIntradayEligible(exchange);
    }

    /**
     * Example: GET /market/cautionary
     * ASM/GSM (Additional/Graded Surveillance Measure) caution-flagged scrips, straight
     * from AngelOne — no params, it's always the current full list.
     */
    @GetMapping("/cautionary")
    public AngelEnvelope<Object> cautionary() {
        return marketDataService.getCautionaryScrips();
    }
}
