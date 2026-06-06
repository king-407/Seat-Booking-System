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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingSeatRepository bookingSeatRepository;

    @Mock
    private ConfirmedSeatRepository confirmedSeatRepository;

    @Mock
    private RedisSeatLockService redisSeatLockService;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private BookingService bookingService;

    @Test
    void createBookingCreatesPendingBookingAndLocksSeats() {
        BookingRequest request = bookingRequest();
        List<BookingSeat> persistedSeats = bookingSeats(10L, BookingSeatStatus.PENDING);

        when(bookingRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setId(10L);
            return booking;
        });
        when(bookingSeatRepository.findByBookingId(10L)).thenReturn(persistedSeats);

        BookingResponse response = bookingService.createBooking(request);

        assertThat(response.getBookingId()).isEqualTo(10L);
        assertThat(response.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.getSeatNumbers()).containsExactly("A1", "A2");

        verify(redisSeatLockService).lockSeats(anyLong(), any(), anyString());

        ArgumentCaptor<List<BookingSeat>> seatsCaptor = ArgumentCaptor.forClass(List.class);
        verify(bookingSeatRepository).saveAll(seatsCaptor.capture());
        assertThat(seatsCaptor.getValue())
                .extracting(BookingSeat::getStatus)
                .containsExactly(BookingSeatStatus.PENDING, BookingSeatStatus.PENDING);
    }

    @Test
    void createBookingReturnsExistingBookingForSameIdempotencyKey() {
        Booking existingBooking = booking(10L, BookingStatus.PENDING, LocalDateTime.now().plusMinutes(5));
        when(bookingRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existingBooking));
        when(bookingSeatRepository.findByBookingId(10L)).thenReturn(bookingSeats(10L, BookingSeatStatus.PENDING));

        BookingResponse response = bookingService.createBooking(bookingRequest());

        assertThat(response.getBookingId()).isEqualTo(10L);
        assertThat(response.getSeatNumbers()).containsExactly("A1", "A2");
        verify(redisSeatLockService, never()).lockSeats(anyLong(), any(), anyString());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void initiatePaymentCreatesOutboxEventAndMovesBookingToPaymentRequested() {
        Booking booking = booking(10L, BookingStatus.PENDING, LocalDateTime.now().plusMinutes(5));
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(bookingSeatRepository.findByBookingId(10L)).thenReturn(bookingSeats(10L, BookingSeatStatus.PENDING));

        PaymentInitiationResponse response = bookingService.initiatePayment(10L);

        assertThat(response.getStatus()).isEqualTo(BookingStatus.PAYMENT_REQUESTED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAYMENT_REQUESTED);

        ArgumentCaptor<PaymentRequestedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRequestedEvent.class);
        verify(outboxService).createPaymentRequestedEvent(anyLong(), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getSeatNumbers()).containsExactly("A1", "A2");
        assertThat(eventCaptor.getValue().getAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void initiatePaymentExpiresBookingWhenHoldAlreadyExpired() {
        Booking booking = booking(10L, BookingStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        List<BookingSeat> seats = bookingSeats(10L, BookingSeatStatus.PENDING);
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(bookingSeatRepository.findByBookingId(10L)).thenReturn(seats);

        assertThatThrownBy(() -> bookingService.initiatePayment(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Booking has expired");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(seats).extracting(BookingSeat::getStatus)
                .containsExactly(BookingSeatStatus.EXPIRED, BookingSeatStatus.EXPIRED);
        verify(redisSeatLockService).releaseSeats(anyLong(), any(), any());
        verify(outboxService, never()).createPaymentRequestedEvent(anyLong(), any());
    }

    @Test
    void handlePaymentSucceededConfirmsBookingSeatsAndReleasesLock() {
        Booking booking = booking(10L, BookingStatus.PAYMENT_REQUESTED, LocalDateTime.now().plusMinutes(5));
        List<BookingSeat> seats = bookingSeats(10L, BookingSeatStatus.PENDING);
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(bookingSeatRepository.findByBookingId(10L)).thenReturn(seats);

        bookingService.handlePaymentSucceeded(PaymentSucceededEvent.builder().bookingId(10L).build());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seats).extracting(BookingSeat::getStatus)
                .containsExactly(BookingSeatStatus.CONFIRMED, BookingSeatStatus.CONFIRMED);
        verify(confirmedSeatRepository, times(2)).save(any(ConfirmedSeat.class));
        verify(redisSeatLockService).releaseSeats(anyLong(), any(), any());
    }

    @Test
    void handlePaymentFailedCancelsBookingSeatsAndReleasesLock() {
        Booking booking = booking(10L, BookingStatus.PAYMENT_REQUESTED, LocalDateTime.now().plusMinutes(5));
        List<BookingSeat> seats = bookingSeats(10L, BookingSeatStatus.PENDING);
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(bookingSeatRepository.findByBookingId(10L)).thenReturn(seats);

        bookingService.handlePaymentFailed(PaymentFailedEvent.builder().bookingId(10L).build());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seats).extracting(BookingSeat::getStatus)
                .containsExactly(BookingSeatStatus.CANCELLED, BookingSeatStatus.CANCELLED);
        verify(redisSeatLockService).releaseSeats(anyLong(), any(), any());
    }

    private BookingRequest bookingRequest() {
        BookingRequest request = new BookingRequest();
        request.setUserId(7L);
        request.setTripId(99L);
        request.setSeatNumbers(List.of("A1", "A2"));
        request.setAmount(new BigDecimal("250.00"));
        request.setIdempotencyKey("idem-1");
        return request;
    }

    private Booking booking(Long id, BookingStatus status, LocalDateTime expiresAt) {
        return Booking.builder()
                .id(id)
                .userId(7L)
                .tripId(99L)
                .totalAmount(new BigDecimal("250.00"))
                .status(status)
                .idempotencyKey("idem-1")
                .lockToken("lock-token-1")
                .expiresAt(expiresAt)
                .build();
    }

    private List<BookingSeat> bookingSeats(Long bookingId, BookingSeatStatus status) {
        return List.of(
                BookingSeat.builder()
                        .bookingId(bookingId)
                        .tripId(99L)
                        .seatNumber("A1")
                        .status(status)
                        .build(),
                BookingSeat.builder()
                        .bookingId(bookingId)
                        .tripId(99L)
                        .seatNumber("A2")
                        .status(status)
                        .build()
        );
    }
}
