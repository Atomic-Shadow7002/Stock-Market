package com.luffy.trading.angelone;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the AngelOne RestClient bean used by every proxy controller.
 * EnableConfigurationProperties here rather than relying on a global scan so
 * that adding this module doesn't accidentally affect other property scans.
 */
@Configuration
@EnableConfigurationProperties(AngelOneProperties.class)
@Slf4j
public class AngelOneConfig {

    /**
     * WHY a dedicated bean instead of using RestClient.create() inline in each
     * controller: the base URL and the X-Internal-Api-Key header are identical
     * for every call, so factoring them out here means they can be changed in
     * exactly one place and every proxy controller picks up the change
     * automatically.
     */
    @Bean
    public RestClient angelOneRestClient(AngelOneProperties props) {
        log.info("Configuring Angel One REST client with base URL: {}", props.baseUrl());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("X-Internal-Api-Key", props.internalApiKey())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
