package com.angelone.angelone_market_service.broadcast;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final PublicFeedWebSocketHandler feedHandler;

    // Comma-separated list of origins allowed to open /ws/feed directly — e.g. your
    // deployed frontend's origin. If your trading backend proxies this connection
    // server-side instead of the browser connecting directly, this can stay tight
    // (server-to-server calls aren't subject to browser CORS/origin checks anyway).
    @Value("${feed.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(feedHandler, "/ws/feed")
                .setAllowedOrigins(allowedOrigins.split(","));
    }
}
