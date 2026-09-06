package com.angelone.angelone_market_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// WHY a single properties class: everything AngelOne-account-related lives here so
// there's exactly one place to look when rotating the TOTP secret, API key, etc.
@ConfigurationProperties(prefix = "angel")
public record AngelProperties(
        String clientCode,
        String pin,
        String totpSecret,
        String apiKey,
        String localIp,
        String publicIp,
        String macAddress,
        String restBaseUrl,
        String wsUrl,
        String dailyReloginCron,
        long tokenRefreshIntervalMs
) {
}
