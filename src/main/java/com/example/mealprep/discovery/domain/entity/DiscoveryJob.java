package com.example.mealprep.discovery.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Aggregate root for an async discovery job. State machine {@code QUEUED → RUNNING → SUCCEEDED |
 * FAILED | PARTIAL}. {@code constraintsJson} carries the frozen-at-enqueue {@code
 * DiscoveryConstraints} as opaque {@link JsonNode}; the service layer reads/writes the typed
 * record via Jackson.
 *
 * <p>{@code sourcesRequested}, {@code sourcesSucceeded}, {@code sourcesFailed}: spec calls for
 * {@code text[]}; per ticket invariant 9 the repo has hit repeated Hibernate text[] flakiness on
 * Spring Boot 3.2.5 (preference / nutrition / recipe all fell back to JSONB list-of-strings). This
 * entity adopts the same JSONB fallback upfront — column type is a private implementation detail,
 * the columns are write-once at job completion, and aligning with the rest of the repo skips an
 * iteration of the verify loop.
 */
@Entity
@Table(name = "discovery_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DiscoveryJob {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger", nullable = false, length = 32)
  private DiscoveryJobTrigger trigger;

  @Column(name = "requested_count", nullable = false)
  private int requestedCount;

  @Type(JsonBinaryType.class)
  @Column(name = "constraints_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode constraintsJson;

  @Type(JsonBinaryType.class)
  @Column(name = "sources_requested", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> sourcesRequested = new ArrayList<>();

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private DiscoveryJobStatus status;

  @Column(name = "queued_at", nullable = false)
  private Instant queuedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "candidates_seen", nullable = false)
  private int candidatesSeen;

  @Column(name = "candidates_after_filter", nullable = false)
  private int candidatesAfterFilter;

  @Column(name = "recipes_ingested", nullable = false)
  private int recipesIngested;

  @Column(name = "recipes_skipped_duplicate", nullable = false)
  private int recipesSkippedDuplicate;

  @Type(JsonBinaryType.class)
  @Column(name = "sources_succeeded", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> sourcesSucceeded = new ArrayList<>();

  @Type(JsonBinaryType.class)
  @Column(name = "sources_failed", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> sourcesFailed = new ArrayList<>();

  @Column(name = "error_summary", columnDefinition = "text")
  private String errorSummary;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
