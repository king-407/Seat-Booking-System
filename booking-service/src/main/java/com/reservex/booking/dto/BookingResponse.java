package com.reservex.booking.dto;

import com.reservex.booking.enums.BookingStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class BookingResponse {
    private Long bookingId;
    private Long userId;
    private Long tripId;
    private List<String> seatNumbers;
    private BigDecimal amount;
    private BookingStatus status;
    private LocalDateTime expiresAt;
}