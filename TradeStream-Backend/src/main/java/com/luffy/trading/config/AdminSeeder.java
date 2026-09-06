package com.luffy.trading.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.luffy.trading.user.Role;
import com.luffy.trading.user.User;
import com.luffy.trading.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * WHAT: Runs once on every application startup. If zero ADMIN-role users
 * exist yet, creates exactly one from AdminSeedProperties.
 * WHY an ApplicationRunner instead of a SQL migration: this needs the real
 * PasswordEncoder bean to produce a correct bcrypt hash — a migration file
 * can only run raw SQL, so it would need a hash precomputed and pasted in
 * ahead of time (either committing a real password's hash to source
 * control, or a hand-typed hash nobody can verify is valid). Doing it in
 * application code sidesteps both problems and is naturally idempotent —
 * existsByRole(ADMIN) is checked every boot, so this never creates a
 * second admin once one exists.
 * WHY the seeded account starts phoneVerified=true: it isn't going through
 * /auth/register, so there's no OTP step to complete — it needs to be able
 * to log in immediately.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminSeedProperties adminSeedProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.ADMIN)) {
            return; // already bootstrapped — nothing to do
        }

        if (!StringUtils.hasText(adminSeedProperties.getPhone())
                || !StringUtils.hasText(adminSeedProperties.getPassword())) {
            log.warn("No ADMIN account exists and admin.seed.phone/password are not configured — "
                    + "skipping admin bootstrap. Set ADMIN_SEED_PHONE / ADMIN_SEED_PASSWORD "
                    + "(or admin.seed.* in application.yml) and restart to create the first admin.");
            return;
        }

        User admin = User.builder()
                .firstName(StringUtils.hasText(adminSeedProperties.getFirstName())
                        ? adminSeedProperties.getFirstName() : "Admin")
                .lastName(StringUtils.hasText(adminSeedProperties.getLastName())
                        ? adminSeedProperties.getLastName() : "User")
                .phone(adminSeedProperties.getPhone())
                .email(StringUtils.hasText(adminSeedProperties.getEmail())
                        ? adminSeedProperties.getEmail() : null)
                .password(passwordEncoder.encode(adminSeedProperties.getPassword()))
                .role(Role.ADMIN)
                .phoneVerified(true)
                .emailVerified(false)
                .build();

        userRepository.save(admin);

        log.warn("Bootstrapped first ADMIN account with phone {} — "
                + "change its password via the app as soon as possible.",
                admin.getPhone());
    }
}
