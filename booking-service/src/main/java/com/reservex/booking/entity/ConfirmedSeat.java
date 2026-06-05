package com.reservex.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "confirmed_seats",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_trip_seat",
                columnNames = {"trip_id", "seat_number"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmedSeat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long bookingId;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}