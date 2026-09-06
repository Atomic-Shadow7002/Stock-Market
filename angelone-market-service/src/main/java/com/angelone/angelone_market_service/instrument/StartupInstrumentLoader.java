package com.angelone.angelone_market_service.instrument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * WHY no @Order here: this used to carry @Order(10) with a comment claiming it ran
 * "after StartupLoginRunner" — that was wrong. Spring gives unordered beans
 * Ordered.LOWEST_PRECEDENCE (i.e. last), so @Order(10) would actually have made THIS
 * run first, the opposite of the stated intent. Removed rather than fixed, because the
 * ordering genuinely doesn't matter: this fetches from margincalculator.angelone.in
 * (public, unauthenticated) and has zero dependency on AngelOne login succeeding —
 * unlike, say, a market-data call, which does need a session. If a real ordering
 * requirement ever shows up, use @Order on both runners explicitly rather than relying
 * on one's default.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupInstrumentLoader implements ApplicationRunner {

    private final InstrumentService instrumentService;

    @Override
    public void run(ApplicationArguments args) {
        // Disk first: instant, works even if AngelOne's dump endpoint is down.
        // WHY wrapped in try/catch (previously wasn't): an ApplicationRunner that throws
        // uncaught kills the whole Spring Boot startup sequence — in @SpringBootTest this
        // surfaces as "Failed to load ApplicationContext" with the real cause buried
        // underneath. Disk-cache load failing must never be allowed to take the app down;
        // it's supposed to be the SAFE fallback path.
        try {
            instrumentService.loadFromDisk();
        } catch (Exception e) {
            log.error("Startup instrument disk-cache load failed — continuing with an empty index until live refresh succeeds", e);
        }

        try {
            instrumentService.refreshFromAngelOne();
        } catch (Exception e) {
            // Don't crash the whole app on boot if AngelOne is briefly unreachable —
            // whatever loadFromDisk() found (possibly nothing) keeps serving until the
            // next daily cron tick or a manual POST /internal/instruments/refresh.
            log.error("Startup instrument live refresh failed — serving disk snapshot (if any) until next scheduled refresh or manual retry", e);
        }
    }
}
