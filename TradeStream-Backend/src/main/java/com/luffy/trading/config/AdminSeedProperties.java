package com.luffy.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * WHAT: Config for the very first admin account, read from
 * application.yml / env vars — same pattern as JwtProperties.
 * WHY config-driven instead of a hardcoded SQL insert: AdminSeeder (which
 * reads these) creates the account using the real PasswordEncoder bean at
 * startup, so the password is always hashed correctly, and the plaintext
 * password never has to live inside a migration file or get committed to
 * git as a precomputed hash.
 */
@Component
@ConfigurationProperties(prefix = "admin.seed")
@Getter
@Setter
public class AdminSeedProperties {

    private String phone;
    private String password;
    private String firstName;
    private String lastName;
    private String email; // optional
}
