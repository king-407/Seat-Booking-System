package com.reservex.booking.repository;

import com.reservex.booking.entity.ConfirmedSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfirmedSeatRepository extends JpaRepository<ConfirmedSeat, Long> {
    boolean existsByTripIdAndSeatNumber(Long tripId, String seatNumber);
}