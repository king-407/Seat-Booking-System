package com.reservex.booking.service;

import com.reservex.booking.dto.BookingRequest;
import com.reservex.booking.dto.BookingResponse;
import com.reservex.booking.entity.Booking;
import com.reservex.booking.entity.BookingSeat;
import com.reservex.booking.entity.ConfirmedSeat;
import com.reservex.booking.enums.BookingSeatStatus;
import com.reservex.booking.enums.BookingStatus;
import com.reservex.booking.repository.BookingRepository;
import com.reservex.booking.repository.BookingSeatRepository;
import com.reservex.booking.repository.ConfirmedSeatRepository;
import com.reservex.events.dto.BookingCreatedEvent;
import com.reservex.events.dto.PaymentFailedEvent;
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
        return bookingRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .map(this::buildResponse)
                .orElseGet(() -> createNewBooking(request));
    }

    private BookingResponse createNewBooking(BookingRequest request) {
        validateSeatsNotConfirmed(request.getTripId(), request.getSeatNumbers());
        redisSeatLockService.lockSeats(request.getTripId(), request.getSeatNumbers());

        try {
            Booking booking = Booking.builder()
                    .userId(request.getUserId())
                    .tripId(request.getTripId())
                    .totalAmount(request.getAmount())
                    .status(BookingStatus.PENDING)
                    .idempotencyKey(request.getIdempotencyKey())
                    .expiresAt(LocalDateTime.now().plusMinutes(5))
                    .build();

            Booking savedBooking = bookingRepository.save(booking);

            List<BookingSeat> seats = request.getSeatNumbers().stream()
                    .map(seat -> BookingSeat.builder()
                            .bookingId(savedBooking.getId())
                            .tripId(savedBooking.getTripId())
                            .seatNumber(seat)
                            .status(BookingSeatStatus.PENDING)
                            .build())
                    .toList();

            bookingSeatRepository.saveAll(seats);

            BookingCreatedEvent event = BookingCreatedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(EventType.BOOKING_CREATED)
                    .bookingId(savedBooking.getId())
                    .userId(savedBooking.getUserId())
                    .tripId(savedBooking.getTripId())
                    .seatNumbers(request.getSeatNumbers())
                    .amount(savedBooking.getTotalAmount())
                    .createdAt(LocalDateTime.now())
                    .build();

            outboxService.createBookingCreatedEvent(savedBooking.getId(), event);

            return buildResponse(savedBooking);

        } catch (Exception ex) {
            redisSeatLockService.releaseSeats(request.getTripId(), request.getSeatNumbers());
            throw ex;
        }
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
        List<String> seatNumbers = seats.stream().map(BookingSeat::getSeatNumber).toList();

        if (booking.getStatus() != BookingStatus.PENDING) {
            return;
        }

        if (!booking.getExpiresAt().isAfter(LocalDateTime.now())) {
            booking.setStatus(BookingStatus.REFUND_REQUIRED);
            bookingRepository.save(booking);
            redisSeatLockService.releaseSeats(booking.getTripId(), seatNumbers);
            return;
        }

        try {
            for (BookingSeat seat : seats) {
                ConfirmedSeat confirmedSeat = ConfirmedSeat.builder()
                        .bookingId(booking.getId())
                        .tripId(seat.getTripId())
                        .seatNumber(seat.getSeatNumber())
                        .build();

                confirmedSeatRepository.save(confirmedSeat);
            }

            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);

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

        if (booking.getStatus() != BookingStatus.PENDING) {
            return;
        }

        List<BookingSeat> seats = bookingSeatRepository.findByBookingId(booking.getId());
        List<String> seatNumbers = seats.stream().map(BookingSeat::getSeatNumber).toList();

        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        for (BookingSeat seat : seats) {
            seat.setStatus(BookingSeatStatus.CANCELLED);
        }

        bookingSeatRepository.saveAll(seats);
        redisSeatLockService.releaseSeats(booking.getTripId(), seatNumbers);
    }

    private BookingResponse buildResponse(Booking booking) {
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