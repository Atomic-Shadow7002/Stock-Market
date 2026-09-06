package com.angelone.angelone_market_service.broadcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.angelone.angelone_market_service.broadcast.FeedMessages.ErrorFrame;
import com.angelone.angelone_market_service.broadcast.FeedMessages.SubscribeRequest;
import com.angelone.angelone_market_service.broadcast.FeedMessages.TickFrame;
import com.angelone.angelone_market_service.feed.AngelFeedClient;
import com.angelone.angelone_market_service.feed.SubscriptionManager;
import com.angelone.angelone_market_service.feed.SubscriptionManager.Key;
import com.angelone.angelone_market_service.feed.Tick;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WHY this is the ONLY thing your main trading backend (or a browser) should ever talk to
 * for live prices: it holds no AngelOne credentials and knows nothing about TOTP, JWTs, or
 * binary parsing. It just accepts subscribe/unsubscribe JSON requests and streams back JSON
 * ticks. Every consumer connects here identically, regardless of how many are watching the
 * same symbol — the actual upstream AngelOne subscription is deduplicated via SubscriptionManager.
 *
 * Protocol (client -> server, one JSON object per WebSocket text frame):
 *   {"action":"subscribe",   "exchangeType":1, "token":"3045", "mode":1}
 *   {"action":"unsubscribe", "exchangeType":1, "token":"3045", "mode":1}
 *
 * Protocol (server -> client):
 *   {"type":"tick",  "tick": { ... Tick fields ... }}
 *   {"type":"error", "message": "..."}
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PublicFeedWebSocketHandler extends TextWebSocketHandler {

    private final AngelFeedClient angelFeedClient;
    private final SubscriptionManager subscriptionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Fan-out index: which local sessions care about a given (exchangeType, token, mode)
    private final ConcurrentHashMap<Key, Set<WebSocketSession>> subscribersByKey = new ConcurrentHashMap<>();
    // Reverse index: what a given session is subscribed to, so we can clean up on disconnect
    private final ConcurrentHashMap<String, Set<Key>> subscriptionsBySession = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        angelFeedClient.addListener(this::fanOut);
    }

    private void fanOut(Tick tick) {
        Key key = new Key(tick.exchangeType(), tick.token(), tick.subscriptionMode());
        Set<WebSocketSession> subscribers = subscribersByKey.get(key);
        if (subscribers == null || subscribers.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(new TickFrame(tick));
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : subscribers) {
                if (session.isOpen()) {
                    synchronized (session) { // WebSocketSession.sendMessage isn't thread-safe for concurrent senders
                        session.sendMessage(message);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast tick to consumers", e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            SubscribeRequest req = objectMapper.readValue(message.getPayload(), SubscribeRequest.class);
            Key key = new Key(req.exchangeType(), req.token(), req.mode());

            if ("subscribe".equalsIgnoreCase(req.action())) {
                subscribersByKey.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(session);
                subscriptionsBySession.computeIfAbsent(session.getId(), k -> new CopyOnWriteArraySet<>()).add(key);

                boolean isNewUpstream = subscriptionManager.addWatcher(key.exchangeType(), key.token(), key.mode());
                if (isNewUpstream) {
                    angelFeedClient.sendSubscription(1, key.mode(), key.exchangeType(), List.of(key.token()));
                }

            } else if ("unsubscribe".equalsIgnoreCase(req.action())) {
                unsubscribe(session, key);
            } else {
                sendError(session, "unknown action: " + req.action());
            }
        } catch (Exception e) {
            sendError(session, "malformed request: " + e.getMessage());
        }
    }

    private void unsubscribe(WebSocketSession session, Key key) {
        Set<WebSocketSession> subs = subscribersByKey.get(key);
        if (subs != null) subs.remove(session);

        Set<Key> sessionKeys = subscriptionsBySession.get(session.getId());
        if (sessionKeys != null) sessionKeys.remove(key);

        boolean lastUpstreamWatcher = subscriptionManager.removeWatcher(key.exchangeType(), key.token(), key.mode());
        if (lastUpstreamWatcher) {
            angelFeedClient.sendSubscription(0, key.mode(), key.exchangeType(), List.of(key.token()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // Clean up everything this session was watching so upstream subscriptions
        // that nobody needs anymore get released back to the 1000-subscription quota.
        Set<Key> keys = subscriptionsBySession.remove(session.getId());
        if (keys == null) return;
        for (Key key : keys) {
            Set<WebSocketSession> subs = subscribersByKey.get(key);
            if (subs != null) subs.remove(session);
            boolean lastUpstreamWatcher = subscriptionManager.removeWatcher(key.exchangeType(), key.token(), key.mode());
            if (lastUpstreamWatcher) {
                angelFeedClient.sendSubscription(0, key.mode(), key.exchangeType(), List.of(key.token()));
            }
        }
    }

    private void sendError(WebSocketSession session, String msg) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(new ErrorFrame(msg))));
        } catch (Exception ignored) {
        }
    }
}
