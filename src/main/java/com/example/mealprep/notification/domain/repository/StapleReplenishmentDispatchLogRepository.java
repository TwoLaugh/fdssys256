package com.example.mealprep.notification.domain.repository;

import com.example.mealprep.notification.scanner.internal.entity.StapleReplenishmentDispatchLog;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link StapleReplenishmentDispatchLog}. Module-private; cross-module access is
 * forbidden by {@code NotificationBoundaryTest}.
 */
public interface StapleReplenishmentDispatchLogRepository
    extends JpaRepository<StapleReplenishmentDispatchLog, UUID> {

  /** Idempotency guard — true once this user's replenishment alert has fired on the scan date. */
  boolean existsByUserIdAndScanDate(UUID userId, LocalDate scanDate);

  /** Retention sweep — delete rows whose {@code fired_at} precedes the cutoff. */
  @Modifying
  @Query("delete from StapleReplenishmentDispatchLog l where l.firedAt < :cutoff")
  int deleteByFiredAtBefore(@Param("cutoff") Instant cutoff);
}
