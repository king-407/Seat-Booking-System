package com.reservex.booking.kafka;

import com.reservex.booking.entity.OutboxEvent;
import com.reservex.booking.enums.OutboxStatus;
import com.reservex.booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingOutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventProcessor outboxEventProcessor;

    @Value("${app.kafka.payment-requested-topic}")
    private String paymentRequestedTopic;

    @Scheduled(fixedRate = 5000)
    public void publishPendingEvents() {

        // Find top 20 pending outbox events.
        // These are events that were created in DB but not yet published to Kafka.
        List<OutboxEvent> events = outboxEventRepository
                .findTop20ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        // Try publishing each event one by one.
        for (OutboxEvent event : events) {
            outboxEventProcessor.process(event);
        }
    }

}