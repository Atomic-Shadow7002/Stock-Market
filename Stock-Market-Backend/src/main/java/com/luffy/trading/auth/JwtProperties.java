package com.luffy.trading.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;

    /** Access token TTL in seconds. Default: 900 (15 min) */
    private long accessTokenExpiry;

    /** Refresh token TTL in seconds. Default: 604800 (7 days) */
    private long refreshTokenExpiry;
}