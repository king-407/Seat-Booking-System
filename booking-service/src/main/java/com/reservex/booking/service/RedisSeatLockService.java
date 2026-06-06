package com.reservex.booking.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    /*
     * Lua script for safe lock release.
     *
     * Problem:
     * If we blindly delete a Redis key, an old booking may accidentally delete
     * a new booking's lock.
     *
     * Fix:
     * Delete the key only if the current Redis value matches this booking's lockToken.
     *
     * This check + delete must be atomic, so we use Redis Lua script.
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                            "return redis.call('DEL', KEYS[1]) " +
                            "else return 0 end",
                    Long.class
            );

    public void lockSeats(Long tripId, List<String> seatNumbers, String lockToken) {
        List<String> lockedSeats = new ArrayList<>();

        // Try locking every requested seat.
        // If any seat lock fails, release all seats locked in this request.
        for (String seatNumber : seatNumbers) {
            boolean acquired = lockSeat(tripId, seatNumber, lockToken);

            if (!acquired) {
                releaseSeats(tripId, lockedSeats, lockToken);
                throw new IllegalStateException("Seat already locked: " + seatNumber);
            }

            lockedSeats.add(seatNumber);
        }
    }

    private boolean lockSeat(Long tripId, String seatNumber, String lockToken) {
        String key = buildKey(tripId, seatNumber);

        /*
         * Redis SET NX EX equivalent:
         *
         * SET seat_lock:trip:9001:seat:A1 <lockToken> NX EX 300
         *
         * NX  -> create key only if it does not already exist
         * EX  -> automatically expire the key after configured TTL
         *
         * The value is lockToken, not "LOCKED".
         * This tells us who owns the lock.
         */
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, lockToken, Duration.ofMinutes(lockTtlMinutes));

        return Boolean.TRUE.equals(acquired);
    }

    public void releaseSeats(Long tripId, List<String> seatNumbers, String lockToken) {
        for (String seatNumber : seatNumbers) {
            releaseSeat(tripId, seatNumber, lockToken);
        }
    }

    private void releaseSeat(Long tripId, String seatNumber, String lockToken) {
        String key = buildKey(tripId, seatNumber);

        /*
         * Safe release:
         *
         * Delete the Redis key only if:
         * current Redis value == this booking's lockToken
         *
         * If another booking has already acquired this seat with a different token,
         * this script will not delete that newer lock.
         */
        redisTemplate.execute(
                RELEASE_LOCK_SCRIPT,
                List.of(key),
                lockToken
        );
    }

    private String buildKey(Long tripId, String seatNumber) {
        return "seat_lock:trip:" + tripId + ":seat:" + seatNumber;
    }
}