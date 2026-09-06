package com.angelone.angelone_market_service.client;

import com.angelone.angelone_market_service.config.AngelProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * WHY centralized: every single SmartAPI endpoint (per their docs) requires the same
 * seven headers. Building them in one place means a header format change or IP update
 * only needs to happen here, not in every controller/service that calls out.
 */
@Component
public class AngelHeaders {

    private final AngelProperties props;

    public AngelHeaders(AngelProperties props) {
        this.props = props;
    }

    /** Headers for the initial loginByPassword call — no Authorization token yet. */
    public HttpHeaders forLogin() {
        HttpHeaders headers = base();
        return headers;
    }

    /** Headers for every authenticated call — Authorization must be "Bearer <jwtToken>". */
    public HttpHeaders forAuthenticated(String jwtToken) {
        HttpHeaders headers = base();
        headers.set("Authorization", "Bearer " + jwtToken);
        return headers;
    }

    private HttpHeaders base() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        headers.set("X-ClientLocalIP", props.localIp());
        headers.set("X-ClientPublicIP", props.publicIp());
        headers.set("X-MACAddress", props.macAddress());
        headers.set("X-PrivateKey", props.apiKey());
        return headers;
    }
}
