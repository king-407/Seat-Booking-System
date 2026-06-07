package com.reservex.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservex.events.dto.PaymentFailedEvent;
import com.reservex.events.dto.PaymentSucceededEvent;
import com.reservex.payment.entity.OutboxEvent;
import com.reservex.payment.enums.OutboxStatus;
import com.reservex.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void createPaymentSucceededEvent(Long paymentId, PaymentSucceededEvent event) {
        saveOutboxEvent(paymentId, "PAYMENT", event.getEventType().name(), event);
    }

    public void createPaymentFailedEvent(Long paymentId, PaymentFailedEvent event) {
        saveOutboxEvent(paymentId, "PAYMENT", event.getEventType().name(), event);
    }

    private void saveOutboxEvent(
            Long aggregateId,
            String aggregateType,
            String eventType,
            Object payload
    ) {
        try {
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .aggregateType(aggregateType)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();

            outboxEventRepository.save(outboxEvent);

        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialize payment outbox event", ex);
        }
    }
}