package com.example.mealprep.feedback.domain.entity;

import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
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
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One row per (feedback, destination) pair. Append-only-with-status-update: only {@code status},
 * {@code actionTaken}, {@code destinationResultJson}, {@code completedAt}, {@code supersededById},
 * {@code failureKind}, {@code failureMessage} are ever updated post-insert.
 *
 * <p>Notably no {@code @Version} and no {@code @LastModifiedDate} — concurrency on the log is by
 * single-writer-per-row (the router code), and the entity is conceptually append-with-status so
 * there's no general "updated_at" semantic. {@code supersededById} is a raw UUID, not a
 * self-{@code @ManyToOne} — per lld/feedback.md line 212 the Java side stays loose to avoid
 * managing a self-referential association.
 */
@Entity
@Table(name = "feedback_routing_log")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class RoutingLogEntry {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "feedback_entry_id", nullable = false, updatable = false)
  private FeedbackEntry feedbackEntry;

  @Enumerated(EnumType.STRING)
  @Column(name = "destination", nullable = false, length = 16)
  private Destination destination;

  @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
  private BigDecimal confidence;

  @Column(name = "extracted_feedback", nullable = false, columnDefinition = "text")
  private String extractedFeedback;

  @Type(JsonBinaryType.class)
  @Column(name = "structured_payload", nullable = false, columnDefinition = "jsonb")
  private JsonNode structuredPayload;

  @Enumerated(EnumType.STRING)
  @Column(name = "routing_decision", nullable = false, length = 24)
  private RoutingDecision routingDecision;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private RoutingStatus status;

  @Column(name = "action_taken", length = 512)
  private String actionTaken;

  @Type(JsonBinaryType.class)
  @Column(name = "destination_result_json", columnDefinition = "jsonb")
  private JsonNode destinationResultJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_kind", length = 32)
  private RoutingFailureKind failureKind;

  @Column(name = "failure_message", length = 512)
  private String failureMessage;

  @Column(name = "superseded_by")
  private UUID supersededById; // raw UUID — no JPA association (LLD line 212)

  @Column(name = "classification_attempt", nullable = false)
  private int classificationAttempt;

  @Column(name = "routed_at", nullable = false)
  private Instant routedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @CreatedDate
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
