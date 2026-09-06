package com.luffy.trading.angelone;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Angel One market-data service integration.
 *
 * WHY a dedicated record: the backend calls the Angel One service as a trusted
 * internal consumer (server-to-server, not user-to-service). These three values
 * are the entire integration "contract" — the base URL to reach the service, the
 * shared secret that authenticates backend→service calls, and the WebSocket URL
 * for the live feed. Grouping them here means there is exactly one place to look
 * when changing the service address or rotating the shared key.
 */
@ConfigurationProperties(prefix = "angelone")
public record AngelOneProperties(
        // Base URL of the Angel One market-data service (e.g. http://localhost:8081)
        String baseUrl,
        // Shared secret sent as X-Internal-Api-Key on every call — must match
        // InternalProperties.apiKey in the Angel One service's own config.
        String internalApiKey,
        // WebSocket endpoint for the live tick feed (ws://localhost:8081/ws/feed)
        String wsFeedUrl
) {
}
