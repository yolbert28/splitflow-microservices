package dev.yolbert.auth_service.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the existing {@code session} table (migration V3) used to hold opaque
 * refresh tokens as BCrypt hashes.
 *
 * <p>{@link InetAddress} maps to the PostgreSQL {@code inet} column through
 * Hibernate's built-in PostgreSQL inet support.
 */
@Entity
@Table(name = "session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "refresh_token_hash", nullable = false)
    private String refreshTokenHash;

    @Column(name = "ip_address", nullable = false)
    private InetAddress ipAddress;

    @Column(name = "device_info")
    private String deviceInfo;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}