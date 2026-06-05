package com.reservex.booking.repository;

import com.reservex.booking.entity.Booking;
import com.reservex.booking.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByStatusAndExpiresAtBefore(
            BookingStatus status,
            LocalDateTime now
    );
}