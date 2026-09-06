package com.angelone.angelone_market_service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * WHY: This service is never meant to be hit directly by browsers — only by your
 * trading backend (server-to-server). A single shared-secret header is enough;
 * there's no per-user identity concept here, and JWT/OAuth would be overkill for
 * a service with exactly one legitimate caller.
 *
 * The actual browser-facing WebSocket (broadcast.PublicFeedWebSocketHandler) is
 * intentionally NOT covered by this filter — that one is meant to be reachable
 * either directly by the frontend, or proxied through your main backend, depending
 * on how you wire things up.
 */
@Component
@RequiredArgsConstructor
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Api-Key";

    private final InternalProperties internalProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        // Only guard REST control endpoints; the public tick WebSocket has its own path
        // and is handled outside Spring MVC's filter chain scope for /ws/**.
        if (request.getRequestURI().startsWith("/ws/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null || !provided.equals(internalProperties.apiKey())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":false,\"message\":\"missing or invalid " + HEADER + "\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
