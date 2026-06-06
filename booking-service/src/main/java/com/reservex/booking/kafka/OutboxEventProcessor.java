package com.reservex.booking.kafka;

import com.reservex.booking.entity.OutboxEvent;
import com.reservex.booking.enums.OutboxStatus;
import com.reservex.booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.payment-requested-topic}")
    private String paymentRequestedTopic;

    @Transactional
    public void process(OutboxEvent event) {

        // Atomically claim the event.
        // Only one service instance can move it from PENDING -> PROCESSING.
        int rowsUpdated = outboxEventRepository.markAsProcessing(
                event.getId(),
                OutboxStatus.PENDING,
                OutboxStatus.PROCESSING
        );

        // If rowsUpdated = 0, another instance already claimed this event.
        if (rowsUpdated == 0) {
            return;
        }

        try {
            kafkaTemplate.send(
                    paymentRequestedTopic,
                    String.valueOf(event.getAggregateId()),
                    event.getPayload()
            ).get();

            event.setStatus(OutboxStatus.SENT);
            event.setUpdatedAt(LocalDateTime.now());

        } catch (Exception ex) {
            event.setRetryCount(event.getRetryCount() + 1);
            event.setLastError(ex.getMessage());
            event.setUpdatedAt(LocalDateTime.now());

            if (event.getRetryCount() >= 3) {
                event.setStatus(OutboxStatus.FAILED);
            } else {
                event.setStatus(OutboxStatus.PENDING);
            }
        }

        outboxEventRepository.save(event);
    }
}