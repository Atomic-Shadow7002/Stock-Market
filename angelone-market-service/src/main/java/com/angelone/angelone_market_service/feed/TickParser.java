package com.angelone.angelone_market_service.feed;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Binary layout per SmartAPI "WebSocket Streaming 2.0 -> Response Contract -> Section-1) Payload".
 * All multi-byte fields are little-endian. Prices arrive in paise (divide by 100 for rupees;
 * currencies divide by 10,000,000 instead — not handled here since this service targets
 * equities/indices; extend if you add currency-segment support).
 *
 * Packet size tells you the mode that was actually sent back:
 *   51 bytes  -> LTP mode
 *   123 bytes -> Quote mode
 *   379 bytes -> SnapQuote (full) mode — depth/circuit-limit fields not parsed here yet;
 *                add them if/when your charts need order-book depth.
 */
@Component
public class TickParser {

    public Tick parse(byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        int subscriptionMode = buf.get(0) & 0xFF;
        int exchangeType = buf.get(1) & 0xFF;
        String token = readNullTerminatedToken(raw, 2, 25);
        // sequence number (offset 27, 8 bytes) — not currently surfaced, skip
        long exchangeTimestamp = buf.getLong(35);
        double ltp = buf.getLong(43) / 100.0;

        Long lastTradedQty = null;
        Double avgTradedPrice = null;
        Long volumeTradedToday = null;
        Double open = null, high = null, low = null, close = null;

        if (raw.length >= 123) { // Quote mode or richer
            lastTradedQty = buf.getLong(51);
            avgTradedPrice = buf.getLong(59) / 100.0;
            volumeTradedToday = buf.getLong(67);
            // totalBuyQuantity (75, double), totalSellQuantity (83, double) — skip for now
            open = buf.getLong(91) / 100.0;
            high = buf.getLong(99) / 100.0;
            low = buf.getLong(107) / 100.0;
            close = buf.getLong(115) / 100.0;
        }

        return new Tick(subscriptionMode, exchangeType, token, exchangeTimestamp,
                ltp, lastTradedQty, avgTradedPrice, volumeTradedToday, open, high, low, close);
    }

    private String readNullTerminatedToken(byte[] raw, int offset, int maxLen) {
        int end = offset;
        while (end < offset + maxLen && raw[end] != 0) {
            end++;
        }
        return new String(raw, offset, end - offset, StandardCharsets.UTF_8);
    }
}
