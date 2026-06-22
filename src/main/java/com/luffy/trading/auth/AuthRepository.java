package com.luffy.trading.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    // Used for "logout all devices" or when re-issuing tokens on login
    void deleteAllByUserId(UUID userId);
}