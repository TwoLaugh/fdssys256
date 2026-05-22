package com.example.mealprep.notification.scanner.internal.repository;

import com.example.mealprep.notification.scanner.internal.entity.PrepReminderDispatchLog;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link PrepReminderDispatchLog}. Module-private; cross-module access is forbidden
 * by {@code NotificationBoundaryTest}.
 */
public interface PrepReminderDispatchLogRepository
    extends JpaRepository<PrepReminderDispatchLog, UUID> {

  /** Idempotency guard — true once a reminder for this (slot, prep time) has fired. */
  boolean existsBySlotIdAndPrepStepAtTime(UUID slotId, Instant prepStepAtTime);

  /** Retention sweep — delete rows whose {@code fired_at} precedes the cutoff. */
  @Modifying
  @Query("delete from PrepReminderDispatchLog l where l.firedAt < :cutoff")
  int deleteByFiredAtBefore(@Param("cutoff") Instant cutoff);
}
