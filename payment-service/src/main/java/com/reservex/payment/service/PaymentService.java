package com.reservex.payment.service;

import com.reservex.events.dto.PaymentFailedEvent;
import com.reservex.events.dto.PaymentRequestedEvent;
import com.reservex.events.dto.PaymentSucceededEvent;
import com.reservex.events.enums.EventType;
import com.reservex.payment.entity.Payment;
import com.reservex.payment.enums.PaymentStatus;
import com.reservex.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MockPaymentGateway mockPaymentGateway;
    private final OutboxService outboxService;

    @Transactional
    public void handlePaymentRequested(PaymentRequestedEvent event) {

        /*
         * Kafka can deliver the same PAYMENT_REQUESTED event more than once.
         * If payment already exists for this bookingId, we do not process again.
         * UNIQUE(booking_id) is the final DB-level protection.
         */
        if (paymentRepository.findByBookingId(event.getBookingId()).isPresent()) {
            return;
        }

        // Creating payment and marking this as INITIATED initially //
        try {
            Payment payment = Payment.builder()
                    .bookingId(event.getBookingId())
                    .userId(event.getUserId())
                    .amount(event.getAmount())
                    .status(PaymentStatus.INITIATED)
                    .build();

            Payment savedPayment = paymentRepository.save(payment);

            boolean paymentSuccess = mockPaymentGateway.chargePayment();

            if (paymentSuccess) {
                markPaymentSuccess(savedPayment);
            } else {
                markPaymentFailed(savedPayment, "MOCK_PAYMENT_FAILED");
            }

        } catch (DataIntegrityViolationException ex) {
            /*
             * Two duplicate Kafka messages are processed at the same time.
             *
             * Both may initially see no payment row.
             * One insert succeeds.
             * The other hits UNIQUE(booking_id).
             *
             * We ignore because the winning transaction has already created payment.
             */
        }
    }

    private void markPaymentSuccess(Payment payment) {
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setTransactionId("TXN-" + UUID.randomUUID());
        paymentRepository.save(payment);


        // making the payload for the payment succeded event //
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.PAYMENT_SUCCEEDED)
                .bookingId(payment.getBookingId())
                .paymentId(payment.getId())
                .amount(payment.getAmount())
                .transactionId(payment.getTransactionId())
                .createdAt(LocalDateTime.now())
                .build();

        outboxService.createPaymentSucceededEvent(payment.getId(), event);
    }

    private void markPaymentFailed(Payment payment, String failureReason) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(failureReason);
        paymentRepository.save(payment);

        // making the payload for the failure event //
        PaymentFailedEvent event = PaymentFailedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.PAYMENT_FAILED)
                .bookingId(payment.getBookingId())
                .paymentId(payment.getId())
                .amount(payment.getAmount())
                .failureReason(failureReason)
                .createdAt(LocalDateTime.now())
                .build();

        outboxService.createPaymentFailedEvent(payment.getId(), event);
    }
}