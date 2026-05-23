package com.example.mealprep.notification.scanner.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Idempotency row for the {@code PrepReminderScanner} (notification/01b). The {@code (slot_id,
 * prep_step_at_time)} unique key fences a re-fire while the current time stays inside the 15-minute
 * fire window. Append-only; pruned by {@code DispatchLogCleanupScheduler}.
 */
@Entity
@Table(name = "prep_reminder_dispatch_log")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PrepReminderDispatchLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "slot_id", nullable = false)
  private UUID slotId;

  @Column(name = "prep_step_at_time", nullable = false)
  private Instant prepStepAtTime;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "fired_at", nullable = false)
  private Instant firedAt;
}
