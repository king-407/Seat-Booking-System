package com.reservex.payment.repository;

import com.reservex.payment.entity.OutboxEvent;
import com.reservex.payment.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop20ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    @Modifying
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = :processingStatus
            WHERE e.id = :eventId
            AND e.status = :pendingStatus
            """)
    int markAsProcessing(
            Long eventId,
            OutboxStatus pendingStatus,
            OutboxStatus processingStatus
    );
}