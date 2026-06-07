package com.reservex.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservex.events.dto.PaymentFailedEvent;
import com.reservex.events.dto.PaymentSucceededEvent;
import com.reservex.payment.entity.OutboxEvent;
import com.reservex.payment.enums.OutboxStatus;
import com.reservex.payment.repository.OutboxEventRepository;
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
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.payment-succeeded-topic}")
    private String paymentSucceededTopic;

    @Value("${app.kafka.payment-failed-topic}")
    private String paymentFailedTopic;

    @Transactional
    public void process(OutboxEvent event) {

        // Claim event first so multiple payment-service instances
        // do not publish the same outbox event together.
        int rowsUpdated = outboxEventRepository.markAsProcessing(
                event.getId(),
                OutboxStatus.PENDING,
                OutboxStatus.PROCESSING
        );

        if (rowsUpdated == 0) {
            return;
        }

        try {
            String topic = resolveTopic(event);
            Object payload = resolvePayload(event);

            kafkaTemplate.send(
                    topic,
                    String.valueOf(event.getAggregateId()),
                    payload
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

    private String resolveTopic(OutboxEvent event) {
        if ("PAYMENT_SUCCEEDED".equals(event.getEventType())) {
            return paymentSucceededTopic;
        }

        if ("PAYMENT_FAILED".equals(event.getEventType())) {
            return paymentFailedTopic;
        }

        throw new IllegalStateException("Unknown event type: " + event.getEventType());
    }

    private Object resolvePayload(OutboxEvent event) throws Exception {
        if ("PAYMENT_SUCCEEDED".equals(event.getEventType())) {
            return objectMapper.readValue(event.getPayload(), PaymentSucceededEvent.class);
        }

        if ("PAYMENT_FAILED".equals(event.getEventType())) {
            return objectMapper.readValue(event.getPayload(), PaymentFailedEvent.class);
        }

        throw new IllegalStateException("Unknown event type: " + event.getEventType());
    }
}
