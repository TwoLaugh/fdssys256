package com.example.mealprep.notification.domain.repository;

import com.example.mealprep.notification.scanner.internal.entity.NutritionAlertDispatchLog;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link NutritionAlertDispatchLog}. Module-private; cross-module access is
 * forbidden by {@code NotificationBoundaryTest}.
 */
public interface NutritionAlertDispatchLogRepository
    extends JpaRepository<NutritionAlertDispatchLog, UUID> {

  /** Idempotency guard — true once an alert for this (user, date, nutrient) has fired. */
  boolean existsByUserIdAndAlertDateAndNutrientKey(
      UUID userId, LocalDate alertDate, String nutrientKey);

  /** Retention sweep — delete rows whose {@code fired_at} precedes the cutoff. */
  @Modifying
  @Query("delete from NutritionAlertDispatchLog l where l.firedAt < :cutoff")
  int deleteByFiredAtBefore(@Param("cutoff") Instant cutoff);
}
