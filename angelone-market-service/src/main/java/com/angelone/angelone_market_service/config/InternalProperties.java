package com.angelone.angelone_market_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "internal")
public record InternalProperties(String apiKey) {
}
