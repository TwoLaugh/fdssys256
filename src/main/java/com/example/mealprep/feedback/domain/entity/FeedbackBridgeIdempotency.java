package com.example.mealprep.feedback.domain.entity;

import com.example.mealprep.feedback.spi.Destination;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One row per {@code (feedback_id, destination)} the bridges dispatch. The {@code UNIQUE}
 * constraint on those two columns is the idempotency guard: a re-fired event within the 5-minute
 * window finds the existing row and the bridge skips. Per tickets/feedback/01g §3-4.
 *
 * <p>No {@code @Version} — these rows are insert-then-status-update only; concurrent inserts
 * collide on the unique constraint (handled as insert-or-skip), and the status update is
 * single-writer within the same bridge invocation.
 */
@Entity
@Table(name = "feedback_bridge_idempotency")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FeedbackBridgeIdempotency {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "feedback_id", nullable = false, updatable = false)
  private UUID feedbackId;

  @Enumerated(EnumType.STRING)
  @Column(name = "destination", nullable = false, updatable = false, length = 16)
  private Destination destination;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private BridgeDispatchStatus status;

  @Column(name = "dispatched_at", nullable = false)
  private Instant dispatchedAt;
}
