package com.reservex.booking.service;

import com.reservex.booking.entity.Booking;
import com.reservex.booking.entity.BookingSeat;
import com.reservex.booking.enums.BookingSeatStatus;
import com.reservex.booking.enums.BookingStatus;
import com.reservex.booking.repository.BookingRepository;
import com.reservex.booking.repository.BookingSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingExpiryScheduler {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final RedisSeatLockService redisSeatLockService;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void expirePendingBookings() {
        List<Booking> expiredBookings = bookingRepository
                .findByStatusInAndExpiresAtBefore(
                        List.of(BookingStatus.PENDING),
                        LocalDateTime.now());

        for (Booking booking : expiredBookings) {
            List<BookingSeat> seats = bookingSeatRepository.findByBookingId(booking.getId());
            List<String> seatNumbers = seats.stream()
                    .map(BookingSeat::getSeatNumber)
                    .toList();

            booking.setStatus(BookingStatus.EXPIRED);
            bookingRepository.save(booking);

            for (BookingSeat seat : seats) {
                seat.setStatus(BookingSeatStatus.EXPIRED);
            }

            bookingSeatRepository.saveAll(seats);
            redisSeatLockService.releaseSeats(booking.getTripId(), seatNumbers, booking.getLockToken());
        }
    }
}
