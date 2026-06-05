package com.reservex.booking.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RedisSeatLockService {
    private final StringRedisTemplate redisTemplate;

    @Value("${app.booking.lock-ttl-minutes}")
    private long lockTtlMinutes;

    public void lockSeats(Long tripId, List<String> seatNumbers) {
        List<String> lockedSeats = new ArrayList<>();

        for (String seatNumber : seatNumbers) {
            boolean acquired = lockSeat(tripId, seatNumber);

            if (!acquired) {
                releaseSeats(tripId, lockedSeats);
                throw new IllegalStateException("Seat already locked: " + seatNumber);
            }

            lockedSeats.add(seatNumber);
        }
    }

    private boolean lockSeat(Long tripId, String seatNumber) {
        String key = buildKey(tripId, seatNumber);

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, "LOCKED", Duration.ofMinutes(lockTtlMinutes));

        return Boolean.TRUE.equals(acquired);
    }

    public void releaseSeats(Long tripId, List<String> seatNumbers) {
        for (String seatNumber : seatNumbers) {
            redisTemplate.delete(buildKey(tripId, seatNumber));
        }
    }

    private String buildKey(Long tripId, String seatNumber) {
        return "seat_lock:trip:" + tripId + ":seat:" + seatNumber;
    }
}