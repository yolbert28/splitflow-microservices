package dev.yolbert.auth_service.worker;

import dev.yolbert.auth_service.config.RabbitMQConfig;
import dev.yolbert.auth_service.domain.entity.Outbox;
import dev.yolbert.auth_service.domain.entity.OutboxStatus;
import dev.yolbert.auth_service.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate   = rabbitTemplate;
    }

    /**
     * Polls the outbox table every 5 seconds for PENDING events and publishes them
     * to RabbitMQ. Updates each event's status to PUBLISHED or FAILED atomically.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<Outbox> pending = outboxRepository.findByStatus(OutboxStatus.PENDING);

        for (Outbox event : pending) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.USER_EVENTS_EXCHANGE,
                        event.getEventType().toLowerCase().replace("_", "."),
                        event.getPayload()
                );
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(OffsetDateTime.now());
                event.setUpdatedAt(OffsetDateTime.now());
                log.info("Published outbox event {} ({})", event.getId(), event.getEventType());
            } catch (Exception e) {
                event.setStatus(OutboxStatus.FAILED);
                event.setUpdatedAt(OffsetDateTime.now());
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage(), e);
            }
            outboxRepository.save(event);
        }
    }
}
