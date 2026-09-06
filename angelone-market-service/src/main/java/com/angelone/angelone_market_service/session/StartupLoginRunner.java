package com.angelone.angelone_market_service.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupLoginRunner implements ApplicationRunner {

    private final AngelSessionManager sessionManager;

    @Override
    public void run(ApplicationArguments args) {
        try {
            sessionManager.login();
        } catch (Exception e) {
            // Don't crash the whole app on boot if AngelOne is briefly unreachable —
            // the scheduled daily-relogin/refresh jobs and manual /internal/session/login
            // retry path (see SessionController) can recover it.
            log.error("Startup AngelOne login failed — service is up but the feed is DOWN until this recovers", e);
        }
    }
}
