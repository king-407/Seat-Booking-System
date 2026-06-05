package com.reservex.booking.dto;

import com.reservex.booking.enums.BookingStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentInitiationResponse {

    private Long bookingId;
    private BookingStatus status;
    private String message;
}