package com.reservex.booking.kafka;

import com.reservex.booking.service.BookingService;
import com.reservex.events.dto.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentSucceededConsumer {
    private final BookingService bookingService;

    @KafkaListener(
            topics = "${app.kafka.payment-succeeded-topic}",
            groupId = "booking-service-group"
    )
    public void consume(PaymentSucceededEvent event) {
        bookingService.handlePaymentSucceeded(event);
    }
}