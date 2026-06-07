package com.reservex.payment.kafka;

import com.reservex.payment.entity.OutboxEvent;
import com.reservex.payment.enums.OutboxStatus;
import com.reservex.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentOutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventProcessor outboxEventProcessor;

    @Scheduled(fixedRate = 5000)
    public void publishPendingEvents() {

        // Find payment result events that are not yet published to Kafka.
        List<OutboxEvent> events = outboxEventRepository
                .findTop20ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (OutboxEvent event : events) {
            outboxEventProcessor.process(event);
        }
    }
}