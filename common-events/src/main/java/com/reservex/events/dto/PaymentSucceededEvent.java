package com.reservex.events.dto;

import com.reservex.events.enums.EventType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentSucceededEvent {

    private String eventId;
    private EventType eventType;
    private Long bookingId;
    private Long paymentId;
    private BigDecimal amount;
    private String transactionId;
    private LocalDateTime createdAt;
}