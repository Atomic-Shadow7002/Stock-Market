package com.luffy.trading.otp;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface OtpRepository extends JpaRepository<OtpVerification, UUID> {

    Optional<OtpVerification> findByUserIdAndType(UUID userId, OtpType type);

    @Modifying
    @Transactional
    void deleteByUserIdAndType(UUID userId, OtpType type);

    /** Used by OtpCleanupScheduler. Returns number of rows removed. */
    @Modifying
    @Transactional
    @Query("DELETE FROM OtpVerification o WHERE o.expiresAt < :now")
    int deleteAllExpired(OffsetDateTime now);
}
