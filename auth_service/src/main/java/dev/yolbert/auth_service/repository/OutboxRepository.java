package dev.yolbert.auth_service.repository;

import dev.yolbert.auth_service.domain.entity.Outbox;
import dev.yolbert.auth_service.domain.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<Outbox, UUID> {

    List<Outbox> findByStatus(OutboxStatus status);
}
