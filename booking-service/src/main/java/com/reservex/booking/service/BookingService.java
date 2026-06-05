package com.reservex.booking.service;

import com.reservex.booking.dto.BookingRequest;
import com.reservex.booking.dto.BookingResponse;
import com.reservex.booking.dto.PaymentInitiationResponse;
import com.reservex.booking.entity.Booking;
import com.reservex.booking.entity.BookingSeat;
import com.reservex.booking.entity.ConfirmedSeat;
import com.reservex.booking.enums.BookingSeatStatus;
import com.reservex.booking.enums.BookingStatus;
import com.reservex.booking.repository.BookingRepository;
import com.reservex.booking.repository.BookingSeatRepository;
import com.reservex.booking.repository.ConfirmedSeatRepository;
import com.reservex.events.dto.PaymentFailedEvent;
import com.reservex.events.dto.PaymentRequestedEvent;
import com.reservex.events.dto.PaymentSucceededEvent;
import com.reservex.events.enums.EventType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final ConfirmedSeatRepository confirmedSeatRepository;
    private final RedisSeatLockService redisSeatLockService;
    private final OutboxService outboxService;

    @Transactional
    public BookingResponse createBooking(BookingRequest request) {

        // Checking for duplicate Booking //
        return bookingRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .map(this::buildResponse)
                // If not duplicate then create new booking //
                .orElseGet(() -> createNewBooking(request));
    }

    private BookingResponse createNewBooking(BookingRequest request) {

        // Check if seats are already present //
        validateSeatsNotConfirmed(request.getTripId(), request.getSeatNumbers());

        redisSeatLockService.lockSeats(request.getTripId(), request.getSeatNumbers());

        try {

            // building the booking object with PENDING status //
            Booking booking = Booking.builder()
                    .userId(request.getUserId())
                    .tripId(request.getTripId())
                    .totalAmount(request.getAmount())
                    .status(BookingStatus.PENDING)
                    .idempotencyKey(request.getIdempotencyKey())
                    .expiresAt(LocalDateTime.now().plusMinutes(5))
                    .build();

            Booking savedBooking = bookingRepository.save(booking);

            // for each seat number we create a seperate booking seats //
            List<BookingSeat> seats = request.getSeatNumbers()
                    .stream()
                    .map(seat -> BookingSeat.builder()
                            .bookingId(savedBooking.getId())
                            .tripId(savedBooking.getTripId())
                            .seatNumber(seat)
                            .status(BookingSeatStatus.PENDING)
                            .build())
                    .toList();

            // saving all the entries for rhe different seats //
            bookingSeatRepository.saveAll(seats);

            // returning booking //
            return buildResponse(savedBooking);

        } catch (Exception ex) {
            redisSeatLockService.releaseSeats(request.getTripId(), request.getSeatNumbers());
            throw ex;
        }
    }

    @Transactional
    public PaymentInitiationResponse initiatePayment(Long bookingId) {


        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalStateException("Booking not found: " + bookingId));

        // If payment status is PAYMENT_REQUESTED ALREADY then just return saying that
        // payment has been requested already //
        if (booking.getStatus() == BookingStatus.PAYMENT_REQUESTED) {
            return PaymentInitiationResponse.builder()
                    .bookingId(booking.getId())
                    .status(booking.getStatus())
                    .message("Payment is already requested for this booking")
                    .build();
        }

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new IllegalStateException("Payment cannot be initiated for booking status: " + booking.getStatus());
        }

        // If payment the booking has been expired already then  set status
        // as expired for booking as well as for seats //

        if (!booking.getExpiresAt().isAfter(LocalDateTime.now())) {
            booking.setStatus(BookingStatus.EXPIRED);
            bookingRepository.save(booking);

            List<BookingSeat> seats = bookingSeatRepository.findByBookingId(booking.getId());
            for (BookingSeat seat : seats) {
                seat.setStatus(BookingSeatStatus.EXPIRED);
            }
            bookingSeatRepository.saveAll(seats);

            redisSeatLockService.releaseSeats(
                    booking.getTripId(),
                    seats.stream().map(BookingSeat::getSeatNumber).toList()
            );

            throw new IllegalStateException("Booking has expired");
        }


        List<BookingSeat> seats = bookingSeatRepository.findByBookingId(booking.getId());
        List<String> seatNumbers = seats.stream()
                .map(BookingSeat::getSeatNumber)
                .toList();

        // preparing the payment requested event that will be going to the payment service
        //
        PaymentRequestedEvent event = PaymentRequestedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.PAYMENT_REQUESTED)
                .bookingId(booking.getId())
                .userId(booking.getUserId())
                .tripId(booking.getTripId())
                .seatNumbers(seatNumbers)
                .amount(booking.getTotalAmount())
                .createdAt(LocalDateTime.now())
                .build();

        outboxService.createPaymentRequestedEvent(booking.getId(), event);

        booking.setStatus(BookingStatus.PAYMENT_REQUESTED);
        bookingRepository.save(booking);

        return PaymentInitiationResponse.builder()
                .bookingId(booking.getId())
                .status(booking.getStatus())
                .message("Payment request created successfully")
                .build();
    }

    private void validateSeatsNotConfirmed(Long tripId, List<String> seatNumbers) {
        for (String seatNumber : seatNumbers) {
            if (confirmedSeatRepository.existsByTripIdAndSeatNumber(tripId, seatNumber)) {
                throw new IllegalStateException("Seat already confirmed: " + seatNumber);
            }
        }
    }

    @Transactional
    public void handlePaymentSucceeded(PaymentSucceededEvent event) {
        Booking booking = bookingRepository.findById(event.getBookingId())
                .orElseThrow(() -> new IllegalStateException("Booking not found: " + event.getBookingId()));

        List<BookingSeat> seats = bookingSeatRepository.findByBookingId(booking.getId());
        List<String> seatNumbers = seats.stream()
                .map(BookingSeat::getSeatNumber)
                .toList();

        if (booking.getStatus() != BookingStatus.PAYMENT_REQUESTED) {
            return;
        }

        // if payment is succeeded but the booking has expired then we want REFUND_REQUESTED
        // Missing booking seats as expired //
        if (!booking.getExpiresAt().isAfter(LocalDateTime.now())) {
            booking.setStatus(BookingStatus.REFUND_REQUIRED);
            bookingRepository.save(booking);

            // setting booking seats as expired //
            for (BookingSeat seat : seats) {
                seat.setStatus(BookingSeatStatus.EXPIRED);
            }
            bookingSeatRepository.saveAll(seats);

            redisSeatLockService.releaseSeats(booking.getTripId(), seatNumbers);
            return;
        }

        try {
            // if everything is fine then create confirm seats //
            for (BookingSeat seat : seats) {
                ConfirmedSeat confirmedSeat = ConfirmedSeat.builder()
                        .bookingId(booking.getId())
                        .tripId(seat.getTripId())
                        .seatNumber(seat.getSeatNumber())
                        .build();

                confirmedSeatRepository.save(confirmedSeat);
            }

            // Set booking status as confirmed
            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);

            // booking seats as confirmed //
            for (BookingSeat seat : seats) {
                seat.setStatus(BookingSeatStatus.CONFIRMED);
            }

            bookingSeatRepository.saveAll(seats);
            redisSeatLockService.releaseSeats(booking.getTripId(), seatNumbers);

        } catch (DataIntegrityViolationException ex) {
            booking.setStatus(BookingStatus.REFUND_REQUIRED);
            bookingRepository.save(booking);
            redisSeatLockService.releaseSeats(booking.getTripId(), seatNumbers);
        }
    }

    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        Booking booking = bookingRepository.findById(event.getBookingId())
                .orElseThrow(() -> new IllegalStateException("Booking not found: " + event.getBookingId()));

        if (booking.getStatus() != BookingStatus.PAYMENT_REQUESTED) {
            return;
        }

        List<BookingSeat> seats = bookingSeatRepository.findByBookingId(booking.getId());
        List<String> seatNumbers = seats.stream()
                .map(BookingSeat::getSeatNumber)
                .toList();

        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        for (BookingSeat seat : seats) {
            seat.setStatus(BookingSeatStatus.CANCELLED);
        }

        bookingSeatRepository.saveAll(seats);
        redisSeatLockService.releaseSeats(booking.getTripId(), seatNumbers);
    }

    private BookingResponse buildResponse(Booking booking) {

        // querying this to get the seats to display in the BookingResponse //
        List<String> seatNumbers = bookingSeatRepository.findByBookingId(booking.getId())
                .stream()
                .map(BookingSeat::getSeatNumber)
                .toList();

        return BookingResponse.builder()
                .bookingId(booking.getId())
                .userId(booking.getUserId())
                .tripId(booking.getTripId())
                .seatNumbers(seatNumbers)
                .amount(booking.getTotalAmount())
                .status(booking.getStatus())
                .expiresAt(booking.getExpiresAt())
                .build();
    }
}