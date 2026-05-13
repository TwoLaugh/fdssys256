package com.example.mealprep.feedback.domain.entity;

import com.example.mealprep.feedback.domain.document.UiContextDocument;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Aggregate root for one user feedback submission. Owns the {@link RoutingLogEntry} list (cascade
 * all, lazy). {@code @Version} guards concurrency for the root + its child rows together with the
 * router's single-writer-per-row discipline on the log children themselves.
 *
 * <p>{@code text} is mapped as Postgres {@code text} (not {@code varchar}) — feedback is unbounded
 * prose, never truncated per lld/feedback.md line 100. {@code uiContext} is a {@link
 * UiContextDocument} record mapped via {@code @Type(JsonBinaryType.class)} against the JSONB
 * column.
 */
@Entity
@Table(name = "feedback_entries")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class FeedbackEntry {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "text", nullable = false, columnDefinition = "text")
  private String text;

  @Type(JsonBinaryType.class)
  @Column(name = "ui_context", nullable = false, columnDefinition = "jsonb")
  private UiContextDocument uiContext;

  @Enumerated(EnumType.STRING)
  @Column(name = "submission_status", nullable = false, length = 24)
  private SubmissionStatus submissionStatus;

  @Column(name = "classification_attempts", nullable = false)
  private int classificationAttempts;

  @Column(name = "last_classified_at")
  private Instant lastClassifiedAt;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreatedDate
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(
      mappedBy = "feedbackEntry",
      cascade = CascadeType.ALL,
      orphanRemoval = false,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<RoutingLogEntry> routingLog = new ArrayList<>();
}
