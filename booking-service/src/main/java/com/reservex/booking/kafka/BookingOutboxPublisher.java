package com.reservex.booking.kafka;

import com.reservex.booking.entity.OutboxEvent;
import com.reservex.booking.enums.OutboxStatus;
import com.reservex.booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingOutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.payment-requested-topic}")
    private String paymentRequestedTopic;

    @Scheduled(fixedRate = 5000)
    public void publishPendingEvents() {

        // find top 20 elements with status as pending  from outbox //
        List<OutboxEvent> events = outboxEventRepository
                .findTop20ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);


        // one by one sending these events to the kafka topic to payment service //
        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(
                        paymentRequestedTopic,
                        String.valueOf(event.getAggregateId()),
                        event.getPayload()
                ).get();

                // if the data has been sent then mark it as sent //
                event.setStatus(OutboxStatus.SENT);
                event.setUpdatedAt(LocalDateTime.now());

            } catch (Exception ex) {
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(ex.getMessage());
                event.setUpdatedAt(LocalDateTime.now());

                if (event.getRetryCount() >= 3) {
                    event.setStatus(OutboxStatus.FAILED);
                }
            }

            outboxEventRepository.save(event);
        }
    }
}