package dev.yolbert.auth_service.repository;

import dev.yolbert.auth_service.domain.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * Locates a session by its stored SHA-256 token hash. Uses the
     * {@code idx_session_refresh_token_hash} index for O(log n) lookup.
     */
    Optional<Session> findByRefreshTokenHash(String hash);
}