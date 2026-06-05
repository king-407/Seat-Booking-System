package com.reservex.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class BookingRequest {
    @NotNull
    private Long userId;

    @NotNull
    private Long tripId;

    @NotEmpty
    private List<String> seatNumbers;

    @NotNull
    private BigDecimal amount;

    @NotNull
    private String idempotencyKey;
}