package com.reservex.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservex.booking.entity.OutboxEvent;
import com.reservex.booking.enums.OutboxStatus;
import com.reservex.booking.repository.OutboxEventRepository;
import com.reservex.events.dto.PaymentRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void createPaymentRequestedEvent(Long bookingId, PaymentRequestedEvent event) {
        try {

            // adding payment requested as the payload here and marking status
            // as pending //
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(bookingId)
                    .aggregateType("BOOKING")
                    .eventType(event.getEventType().name())
                    .payload(objectMapper.writeValueAsString(event))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();

            outboxEventRepository.save(outboxEvent);

        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialize PaymentRequestedEvent", ex);
        }
    }
}