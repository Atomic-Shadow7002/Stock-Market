package com.angelone.angelone_market_service.feed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WHY reference counting: multiple website users may look at the same symbol at once.
 * We must NOT send AngelOne a duplicate subscribe per user — the quota is 1000 total
 * token/mode subscriptions for our one session, shared across everyone. This class
 * tracks "how many local consumers currently care about (exchangeType, token, mode)"
 * and only calls out to AngelOne when a symbol goes from 0 watchers -> 1, or from
 * 1 watcher -> 0 (unsubscribe).
 */
@Component
@Slf4j
public class SubscriptionManager {

    public record Key(int exchangeType, String token, int mode) {
    }

    private final Map<Key, AtomicInteger> refCounts = new ConcurrentHashMap<>();

    /** Returns true if this is a NEW subscription that AngelOne needs to be told about. */
    public boolean addWatcher(int exchangeType, String token, int mode) {
        Key key = new Key(exchangeType, token, mode);
        AtomicInteger count = refCounts.computeIfAbsent(key, k -> new AtomicInteger(0));
        int newCount = count.incrementAndGet();
        if (newCount == 1) {
            log.debug("First watcher for {} — will subscribe upstream", key);
            return true;
        }
        return false;
    }

    /** Returns true if this was the LAST watcher and AngelOne should be told to unsubscribe. */
    public boolean removeWatcher(int exchangeType, String token, int mode) {
        Key key = new Key(exchangeType, token, mode);
        AtomicInteger count = refCounts.get(key);
        if (count == null) return false;
        int newCount = count.decrementAndGet();
        if (newCount <= 0) {
            refCounts.remove(key);
            log.debug("Last watcher gone for {} — will unsubscribe upstream", key);
            return true;
        }
        return false;
    }

    public int activeSubscriptionCount() {
        return refCounts.size();
    }

    /**
     * All currently-active subscriptions, grouped by (exchangeType, mode) with their token
     * lists. Used to replay subscriptions after a feed reconnect — AngelOne's WebSocket
     * doesn't remember what you were subscribed to on a fresh connection.
     */
    public Map<GroupKey, List<String>> activeGroupedByModeAndExchange() {
        Map<GroupKey, List<String>> grouped = new ConcurrentHashMap<>();
        for (Key key : refCounts.keySet()) {
            grouped.computeIfAbsent(new GroupKey(key.exchangeType(), key.mode()), k -> new ArrayList<>())
                    .add(key.token());
        }
        return grouped;
    }

    public record GroupKey(int exchangeType, int mode) {
    }
}
