package com.example.mealprep.feedback.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Per-user cursor driving the preference AI delta-generation pipeline's BATCH trigger
 * (preference-01g). One row per user.
 *
 * <p>{@code pendingCount} increments on every PREFERENCE-routed feedback; on the {@code
 * batchThreshold}-th (default 5) the orchestrator runs a delta update and resets the counter.
 * {@code lastProcessedFeedbackId} advances to the most-recent processed feedback so a run gathers
 * the batch "since the last cursor". {@code lastRunAt} / {@code lastRunTrigger} record the most
 * recent run; the WEEKLY sweep selects users with {@code pendingCount > 0}.
 *
 * <p>{@code @Version} guards the BATCH increment path against a concurrent run resetting the same
 * row.
 */
@Entity
@Table(name = "feedback_preference_delta_cursor")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PreferenceDeltaCursor {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "last_processed_feedback_id")
  private UUID lastProcessedFeedbackId;

  @Column(name = "pending_count", nullable = false)
  private int pendingCount;

  @Column(name = "last_run_at")
  private Instant lastRunAt;

  @Column(name = "last_run_trigger", length = 16)
  private String lastRunTrigger;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreatedDate
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
