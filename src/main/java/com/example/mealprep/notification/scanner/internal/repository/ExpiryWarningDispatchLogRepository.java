package com.example.mealprep.notification.scanner.internal.repository;

import com.example.mealprep.notification.scanner.internal.entity.ExpiryWarningDispatchLog;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link ExpiryWarningDispatchLog}. Module-private (notification scanner internals);
 * cross-module access is structurally forbidden by {@code NotificationBoundaryTest}.
 */
public interface ExpiryWarningDispatchLogRepository
    extends JpaRepository<ExpiryWarningDispatchLog, UUID> {

  /** Idempotency guard — true once today's expiry warning has fired for the user. */
  boolean existsByUserIdAndScanDate(UUID userId, LocalDate scanDate);

  /** Retention sweep — delete rows whose {@code fired_at} precedes the cutoff. */
  @Modifying
  @Query("delete from ExpiryWarningDispatchLog l where l.firedAt < :cutoff")
  int deleteByFiredAtBefore(@Param("cutoff") Instant cutoff);
}
