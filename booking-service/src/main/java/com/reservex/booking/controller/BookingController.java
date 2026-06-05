package com.reservex.booking.controller;

import com.reservex.booking.dto.BookingRequest;
import com.reservex.booking.dto.BookingResponse;
import com.reservex.booking.dto.PaymentInitiationResponse;
import com.reservex.booking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody BookingRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(bookingService.createBooking(request));
    }

    @PostMapping("/{bookingId}/pay")
    public ResponseEntity<PaymentInitiationResponse> initiatePayment(
            @PathVariable Long bookingId
    ) {
        return ResponseEntity.ok(bookingService.initiatePayment(bookingId));
    }
}