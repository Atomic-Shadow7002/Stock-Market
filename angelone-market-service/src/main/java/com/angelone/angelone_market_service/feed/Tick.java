package com.angelone.angelone_market_service.feed;

/**
 * Fields present depend on subscription mode:
 *  - LTP mode: only ltp is populated (others null/0)
 *  - QUOTE mode: adds open/high/low/close/volume
 *  - SNAP_QUOTE (FULL) mode: adds everything (not modeled in full detail here — extend
 *    as needed, e.g. market depth, if your charts need order-book data)
 */
public record Tick(
        int subscriptionMode,   // 1=LTP, 2=Quote, 3=SnapQuote
        int exchangeType,       // 1=nse_cm, 2=nse_fo, 3=bse_cm, 4=bse_fo, 5=mcx_fo
        String token,
        long exchangeTimestamp,
        double ltp,
        Long lastTradedQty,
        Double avgTradedPrice,
        Long volumeTradedToday,
        Double open,
        Double high,
        Double low,
        Double close
) {
}
