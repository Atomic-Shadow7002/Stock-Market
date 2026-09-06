package com.angelone.angelone_market_service.session;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/internal/session")
@RequiredArgsConstructor
public class SessionController {

    private final AngelSessionManager sessionManager;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("loggedIn", sessionManager.isLoggedIn());
    }

    /** Break-glass endpoint — e.g. if AngelOne invalidated the session unexpectedly. */
    @PostMapping("/relogin")
    public Map<String, Object> relogin() {
        sessionManager.login();
        return Map.of("status", true, "message", "re-login successful");
    }
}
