package com.angelone.angelone_market_service.config;

import com.angelone.angelone_market_service.instrument.InstrumentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({AngelProperties.class, CacheProperties.class, InternalProperties.class, InstrumentProperties.class})
public class AppConfig {

    @Bean
    public RestClient angelRestClient(AngelProperties props) {
        return RestClient.builder()
                .baseUrl(props.restBaseUrl())
                .build();
    }

    /**
     * WHY a separate, header-free RestClient: the scrip master dump lives on a different
     * host (margincalculator.angelone.in) than every other AngelOne call in this service
     * (apiconnect.angelone.in), and it's a plain public file — no X-PrivateKey, no JWT,
     * no auth headers at all. Reusing angelRestClient here would be wrong on both counts
     * (wrong base URL, and it'd imply auth semantics this call doesn't have).
     */
    @Bean
    public RestClient instrumentRestClient() {
        return RestClient.builder().build();
    }

    /**
     * WHY explicit rather than relying on auto-configuration: Spring Boot 4.x defaults to
     * Jackson 3 (tools.jackson.*) for its own auto-configured JSON support, and does NOT
     * auto-configure a classic com.fasterxml.jackson.databind.ObjectMapper bean anymore —
     * even though jackson-databind is on the classpath (that only makes it compilable,
     * not autowireable). InstrumentService needs a plain Jackson 2 ObjectMapper for its own
     * direct disk-cache read/write (readValue/writeValue against a File) — defining it here
     * removes any dependency on which Jackson generation Boot's auto-config happens to
     * prefer for HTTP message conversion elsewhere in the app.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
