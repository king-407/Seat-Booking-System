package com.reservex.booking.kafka;

import com.reservex.booking.service.BookingService;
import com.reservex.events.dto.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentFailedConsumer {
    private final BookingService bookingService;

    @KafkaListener(
            topics = "${app.kafka.payment-failed-topic}",
            groupId = "booking-service-group"
    )
    public void consume(PaymentFailedEvent event) {
        bookingService.handlePaymentFailed(event);
    }
}