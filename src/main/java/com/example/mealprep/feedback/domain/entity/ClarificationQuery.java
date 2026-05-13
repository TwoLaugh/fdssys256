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
import jakarta.persistence.OneToOne;
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
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One row per pending clarification question. {@code classifierOptionsJson} holds the classifier's
 * shortlist as a JSON array of {@code {destination, snippet, classifierJustification}}.
 *
 * <p>{@code @Version} per lld/feedback.md line 213 — the user can answer concurrently with the
 * daily expiry sweep. The {@code @OneToOne} back to {@code FeedbackEntry} expresses the invariant
 * that each feedback entry has at most one pending clarification at a time.
 */
@Entity
@Table(name = "feedback_clarification_queries")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class ClarificationQuery {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "feedback_entry_id", nullable = false, updatable = false)
  private FeedbackEntry feedbackEntry;

  @Type(JsonBinaryType.class)
  @Column(name = "classifier_options_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode classifierOptionsJson;

  @Column(name = "question_text", nullable = false, length = 512)
  private String questionText;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private ClarificationStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "selected_destination", length = 16)
  private Destination selectedDestination;

  @Column(name = "user_clarification_text", columnDefinition = "text")
  private String userClarificationText;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "answered_at")
  private Instant answeredAt;

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
