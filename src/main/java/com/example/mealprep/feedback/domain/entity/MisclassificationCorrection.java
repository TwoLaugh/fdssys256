package com.example.mealprep.feedback.domain.entity;

import com.example.mealprep.feedback.spi.Destination;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Append-only audit of a user-driven correction of a misclassified routing. Copies {@code
 * originalDestination} and {@code originalConfidence} at correction time so the row is a complete
 * labelled example for a future fine-tune pipeline.
 *
 * <p>No {@code @Version}, no {@code @LastModifiedDate} — the row is append-only in spirit. The
 * replayer in feedback-01f flips {@code replayStatus} from {@code PENDING_REPLAY} to a terminal
 * value and fills {@code replayRoutingId}; both fields are deliberately marked nullable / mutable
 * at the JPA level. {@code originalRoutingId} and {@code replayRoutingId} are raw UUIDs (not JPA
 * associations) to avoid an ownership cycle with {@code RoutingLogEntry.supersededById}.
 */
@Entity
@Table(name = "feedback_misclassification_corrections")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MisclassificationCorrection {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "feedback_entry_id", nullable = false, updatable = false)
  private FeedbackEntry feedbackEntry;

  @Column(name = "original_routing_id", nullable = false, updatable = false)
  private UUID originalRoutingId; // raw UUID — see class javadoc

  @Enumerated(EnumType.STRING)
  @Column(name = "corrected_destination", nullable = false, length = 16, updatable = false)
  private Destination correctedDestination;

  @Column(name = "user_correction_note", length = 512, updatable = false)
  private String userCorrectionNote;

  @Column(name = "actor_user_id", nullable = false, updatable = false)
  private UUID actorUserId;

  @Column(
      name = "original_confidence",
      nullable = false,
      precision = 4,
      scale = 3,
      updatable = false)
  private BigDecimal originalConfidence;

  @Enumerated(EnumType.STRING)
  @Column(name = "original_destination", nullable = false, length = 16, updatable = false)
  private Destination originalDestination;

  @Column(name = "replay_routing_id")
  private UUID replayRoutingId; // set by feedback-01f's replayer; nullable until replay completes

  @Enumerated(EnumType.STRING)
  @Column(name = "replay_status", nullable = false, length = 24)
  private CorrectionReplayStatus replayStatus;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @CreatedDate
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
