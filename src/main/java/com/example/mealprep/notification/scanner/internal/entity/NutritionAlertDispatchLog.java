package com.example.mealprep.notification.scanner.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Idempotency row for the {@code NutritionAlertScanner} (notification/01b). The {@code (user_id,
 * alert_date, nutrient_key)} unique key fences a second alert for the same nutrient on the same
 * day. Append-only; pruned by {@code DispatchLogCleanupScheduler}.
 */
@Entity
@Table(name = "nutrition_alert_dispatch_log")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class NutritionAlertDispatchLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "alert_date", nullable = false)
  private LocalDate alertDate;

  @Column(name = "nutrient_key", nullable = false, length = 32)
  private String nutrientKey;

  @Column(name = "fired_at", nullable = false)
  private Instant firedAt;
}
