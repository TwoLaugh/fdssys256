package com.example.mealprep.discovery.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only audit row for a single fetch attempt against a candidate URL. NO {@code @Version}
 * and NO {@code @UpdateTimestamp} (LLD line 195) — rows are written once at the time of the fetch
 * and never mutated.
 *
 * <p>{@code recipeId} is a SOFT foreign key (no {@code @ManyToOne} / {@code REFERENCES} clause to
 * {@code recipe_recipes}) per LLD line 181 — discovery must not pull the recipe module's tables
 * into its Flyway path. {@code occurredAt} is application-set by the runner so wall-clock per-fetch
 * timing is captured precisely (not approximated by {@code @CreationTimestamp}).
 */
@Entity
@Table(name = "discovery_scrape_log")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DiscoveryScrapeLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "job_id", nullable = false, updatable = false)
  private UUID jobId;

  @Column(name = "source_key", nullable = false, length = 64, updatable = false)
  private String sourceKey;

  @Column(name = "candidate_url", nullable = false, length = 2048, updatable = false)
  private String candidateUrl;

  @Column(name = "canonical_url", length = 2048, updatable = false)
  private String canonicalUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32, updatable = false)
  private ScrapeOutcome status;

  @Column(name = "http_status_code", updatable = false)
  private Integer httpStatusCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "robots_txt_outcome", nullable = false, length = 16, updatable = false)
  private RobotsTxtOutcome robotsTxtOutcome;

  @Column(name = "latency_ms", updatable = false)
  private Integer latencyMs;

  @Column(name = "content_fingerprint", length = 64, updatable = false)
  private String contentFingerprint;

  @Column(name = "extraction_method", length = 32, updatable = false)
  private String extractionMethod;

  @Column(name = "extraction_confidence", precision = 4, scale = 3, updatable = false)
  private BigDecimal extractionConfidence;

  @Column(name = "recipe_id", updatable = false)
  private UUID recipeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "skip_reason", length = 64, updatable = false)
  private ScrapeSkipReason skipReason;

  @Column(name = "error_class", length = 64, updatable = false)
  private String errorClass;

  @Column(name = "error_message", columnDefinition = "text", updatable = false)
  private String errorMessage;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;
}
