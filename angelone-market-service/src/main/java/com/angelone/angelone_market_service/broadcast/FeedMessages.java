package com.angelone.angelone_market_service.broadcast;

import com.angelone.angelone_market_service.feed.Tick;

public class FeedMessages {

    /** Incoming from a consumer: {"action":"subscribe","exchangeType":1,"token":"3045","mode":1} */
    public record SubscribeRequest(String action, int exchangeType, String token, int mode) {
    }

    /** Outgoing to consumers: the tick, tagged with a type so clients can distinguish frame kinds. */
    public record TickFrame(String type, Tick tick) {
        public TickFrame(Tick tick) {
            this("tick", tick);
        }
    }

    public record ErrorFrame(String type, String message) {
        public ErrorFrame(String message) {
            this("error", message);
        }
    }
}
