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
 * Idempotency row for the {@code DefrostReminderScanner} (notification/01b). The {@code (slot_id,
 * defrost_target_time)} unique key fences a re-fire while the current time stays inside the 1-hour
 * fire window. Append-only; pruned by {@code DispatchLogCleanupScheduler}.
 */
@Entity
@Table(name = "defrost_reminder_dispatch_log")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DefrostReminderDispatchLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "slot_id", nullable = false)
  private UUID slotId;

  @Column(name = "defrost_target_time", nullable = false)
  private Instant defrostTargetTime;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "fired_at", nullable = false)
  private Instant firedAt;
}
