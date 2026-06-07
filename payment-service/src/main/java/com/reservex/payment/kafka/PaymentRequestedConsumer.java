package com.reservex.payment.kafka;

import com.reservex.events.dto.PaymentRequestedEvent;
import com.reservex.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentRequestedConsumer {

    private final PaymentService paymentService;

    @KafkaListener(
            topics = "${app.kafka.payment-requested-topic}",
            groupId = "payment-service-group"
    )
    public void consume(PaymentRequestedEvent event) {
        paymentService.handlePaymentRequested(event);
    }
}