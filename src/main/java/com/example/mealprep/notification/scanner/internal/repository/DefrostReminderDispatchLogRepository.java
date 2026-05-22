package com.example.mealprep.notification.scanner.internal.repository;

import com.example.mealprep.notification.scanner.internal.entity.DefrostReminderDispatchLog;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link DefrostReminderDispatchLog}. Module-private; cross-module access is
 * forbidden by {@code NotificationBoundaryTest}.
 */
public interface DefrostReminderDispatchLogRepository
    extends JpaRepository<DefrostReminderDispatchLog, UUID> {

  /** Idempotency guard — true once a reminder for this (slot, target time) has fired. */
  boolean existsBySlotIdAndDefrostTargetTime(UUID slotId, Instant defrostTargetTime);

  /** Retention sweep — delete rows whose {@code fired_at} precedes the cutoff. */
  @Modifying
  @Query("delete from DefrostReminderDispatchLog l where l.firedAt < :cutoff")
  int deleteByFiredAtBefore(@Param("cutoff") Instant cutoff);
}
