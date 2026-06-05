package com.reservex.events.dto;

import com.reservex.events.enums.EventType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingCreatedEvent {

    private String eventId;
    private EventType eventType;
    private Long bookingId;
    private Long userId;
    private Long tripId;
    private List<String> seatNumbers;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}