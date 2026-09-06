package com.angelone.angelone_market_service.marketdata;

import java.util.List;
import java.util.Map;

public class MarketDataDtos {

    public record AngelEnvelope<T>(boolean status, String message, String errorcode, T data) {
    }

    // --- Quote ---
    public record QuoteRequest(String mode, Map<String, List<String>> exchangeTokens) {
    }

    // --- Candle ---
    public record CandleRequest(String exchange, String symboltoken, String interval, String fromdate, String todate) {
    }

    // --- Greeks ---
    public record GreeksRequest(String name, String expirydate) {
    }

    // --- Historical OI ---
    /** Same field names/shape as CandleRequest — AngelOne's getOIData uses an identical envelope. */
    public record OiRequest(String exchange, String symboltoken, String interval, String fromdate, String todate) {
    }

    // --- Brokerage Calculator ---
    /**
     * Mirrors AngelOne's estimateCharges request shape exactly (snake_case field names
     * are what the docs specify — Jackson maps these via the @JsonProperty below since
     * Java fields can't be snake_case).
     *
     * `token` is intentionally allowed to be null/omitted on the way IN from the caller —
     * MarketDataService.getBrokerage resolves it from `exchange`+`symbolName` via
     * InstrumentService before this ever gets forwarded to AngelOne (which does require it).
     */
    public record BrokerageOrder(
            @com.fasterxml.jackson.annotation.JsonProperty("product_type") String productType,
            @com.fasterxml.jackson.annotation.JsonProperty("transaction_type") String transactionType,
            String quantity,
            String price,
            String exchange,
            @com.fasterxml.jackson.annotation.JsonProperty("symbol_name") String symbolName,
            String token
    ) {
    }

    public record BrokerageRequest(java.util.List<BrokerageOrder> orders) {
    }

    // --- Margin Calculator ---
    /**
     * The exact shape AngelOne's margin/v1/batch expects — no `symbol` field, since
     * AngelOne only understands tokens here. This is what actually goes out over the wire.
     */
    public record MarginPosition(
            String exchange,
            int qty,
            double price,
            String productType,
            String token,
            String tradeType,
            String orderType
    ) {
    }

    public record MarginRequest(java.util.List<MarginPosition> positions) {
    }

    /**
     * What the CALLER sends in. Adds an optional `symbol` alongside the optional `token` —
     * MarketDataService.getMargin resolves symbol->token via InstrumentService when token
     * is omitted, then maps down to the plain MarginPosition above before calling AngelOne.
     * orderType still has no service-side default — see MarketDataService for why.
     */
    public record MarginPositionInput(
            String exchange,
            int qty,
            double price,
            String productType,
            String token,
            String symbol,
            String tradeType,
            String orderType
    ) {
    }

    public record MarginRequestInput(java.util.List<MarginPositionInput> positions) {
    }
}
