package com.luffy.trading.otp;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.luffy.trading.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Table(
    name = "otp_verifications",
    uniqueConstraints = @UniqueConstraint(name = "uq_otp_user_type", columnNames = {"user_id", "type"})
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtpVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OtpType type;

    /** BCrypt hash of the 6-digit code. The plain code is NEVER persisted. */
    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public boolean isExpired() {
        return expiresAt.isBefore(OffsetDateTime.now());
    }
}
