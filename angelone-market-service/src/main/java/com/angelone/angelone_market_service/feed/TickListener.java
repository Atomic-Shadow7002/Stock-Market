package com.angelone.angelone_market_service.feed;

@FunctionalInterface
public interface TickListener {
    void onTick(Tick tick);
}
