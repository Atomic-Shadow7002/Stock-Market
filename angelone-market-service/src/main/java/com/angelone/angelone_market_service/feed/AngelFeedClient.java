package com.angelone.angelone_market_service.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.angelone.angelone_market_service.config.AngelProperties;
import com.angelone.angelone_market_service.session.AngelSessionManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WHY this is the ONLY thing in the whole system that opens a connection to AngelOne's
 * WebSocket: per SmartAPI docs, each client code may have up to 3 concurrent connections
 * and a combined 1000 token/mode subscription quota. Centralizing to one connection here
 * means every downstream consumer (website users) shares that single quota safely via
 * SubscriptionManager, instead of each accidentally opening their own upstream connection.
 */
@Component
@Slf4j
public class AngelFeedClient {

    private final AngelProperties props;
    private final AngelSessionManager sessionManager;
    private final TickParser tickParser;
    private final SubscriptionManager subscriptionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<WebSocketSession> currentSession = new AtomicReference<>();
    private final StandardWebSocketClient client = new StandardWebSocketClient();

    public AngelFeedClient(AngelProperties props, AngelSessionManager sessionManager,
                            TickParser tickParser, SubscriptionManager subscriptionManager) {
        this.props = props;
        this.sessionManager = sessionManager;
        this.tickParser = tickParser;
        this.subscriptionManager = subscriptionManager;
    }

    @PostConstruct
    void init() {
        // Whenever the session manager gets a fresh feedToken (login OR refresh),
        // the old feed connection's credentials are stale — reconnect.
        sessionManager.setOnSessionRenewed(this::reconnect);
    }

    public void addListener(TickListener listener) {
        listeners.add(listener);
    }

    public synchronized void reconnect() {
        if (!sessionManager.isLoggedIn()) {
            log.warn("Cannot connect feed — no AngelOne session yet");
            return;
        }
        closeQuietly();

        var tokens = sessionManager.currentTokens();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Bearer " + tokens.jwtToken());
        headers.add("x-api-key", props.apiKey());
        headers.add("x-client-code", props.clientCode());
        headers.add("x-feed-token", tokens.feedToken());

        try {
            WebSocketSession session = client.execute(new FeedHandler(), headers, URI.create(props.wsUrl())).get();
            currentSession.set(session);
            log.info("Connected to AngelOne feed stream");
            resubscribeAll();
        } catch (Exception e) {
            log.error("Failed to connect to AngelOne feed stream — will retry on next heartbeat cycle", e);
        }
    }

    /**
     * A fresh AngelOne WebSocket connection remembers nothing — replay every subscription
     * SubscriptionManager currently thinks is active (from before the reconnect) so watchers
     * don't silently go dark after a session renewal or dropped connection.
     */
    private void resubscribeAll() {
        var grouped = subscriptionManager.activeGroupedByModeAndExchange();
        if (grouped.isEmpty()) return;
        log.info("Replaying {} subscription group(s) after reconnect", grouped.size());
        grouped.forEach((key, tokens) -> sendSubscription(1, key.mode(), key.exchangeType(), tokens));
    }

    private void closeQuietly() {
        WebSocketSession old = currentSession.getAndSet(null);
        if (old != null && old.isOpen()) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Every WS connection must send "ping" every 30s or AngelOne drops it. */
    @Scheduled(fixedRate = 25000)
    public void heartbeat() {
        WebSocketSession session = currentSession.get();
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage("ping"));
            } catch (Exception e) {
                log.warn("Heartbeat send failed — reconnecting", e);
                reconnect();
            }
        }
    }

    /**
     * action: 1 = subscribe, 0 = unsubscribe. mode: 1 = LTP, 2 = Quote, 3 = SnapQuote.
     * Call this only when SubscriptionManager confirms it's a net-new / fully-removed watch
     * (see SubscriptionManager) — not once per individual downstream consumer.
     */
    public void sendSubscription(int action, int mode, int exchangeType, List<String> tokens) {
        WebSocketSession session = currentSession.get();
        if (session == null || !session.isOpen()) {
            log.warn("Feed not connected — subscription request dropped, will need re-issue after reconnect");
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "correlationID", "amkt" + System.currentTimeMillis() % 10000000,
                    "action", action,
                    "params", Map.of(
                            "mode", mode,
                            "tokenList", List.of(Map.of(
                                    "exchangeType", exchangeType,
                                    "tokens", tokens
                            ))
                    )
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.error("Failed to send subscription request", e);
        }
    }

    private class FeedHandler extends TextWebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            log.info("AngelOne feed WebSocket opened");
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            // "pong" heartbeat replies, or JSON error frames — log errors, ignore pongs.
            String payload = message.getPayload();
            if (!"pong".equalsIgnoreCase(payload.trim())) {
                log.warn("AngelOne feed sent unexpected text frame (likely an error): {}", payload);
            }
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            try {
                ByteBuffer payload = message.getPayload();
                byte[] raw = new byte[payload.remaining()];
                payload.get(raw);
                Tick tick = tickParser.parse(raw);
                for (TickListener listener : listeners) {
                    listener.onTick(tick);
                }
            } catch (Exception e) {
                log.error("Failed to parse incoming tick packet", e);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("AngelOne feed transport error — will reconnect on next heartbeat", exception);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            log.warn("AngelOne feed connection closed: {}", closeStatus);
        }
    }
}
