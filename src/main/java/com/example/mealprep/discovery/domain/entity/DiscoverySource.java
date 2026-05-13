package com.example.mealprep.discovery.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Aggregate root for a discovery source. Mutable on the bookkeeping fields the runner updates
 * ({@code enabled} via admin, {@code failureStreak} / {@code lastFailureAt} / {@code lastSuccessAt}
 * / {@code lastUsedAt} via the runner, {@code qualityScore} via the rolling-stats job). The
 * runner reads enabled sources once per job — toggles take effect on the next job. {@code
 * crawlConfig} is an opaque {@code JsonNode} (sitemap URL / RSS URL / search engine id), read whole
 * by the source impl.
 */
@Entity
@Table(
    name = "discovery_sources",
    uniqueConstraints = @UniqueConstraint(columnNames = "source_key"))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DiscoverySource {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "source_key", nullable = false, length = 64, updatable = false)
  private String sourceKey;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 16)
  private DiscoverySourceType sourceType;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private DiscoverySourceKind kind;

  @Column(name = "base_url", nullable = false, length = 255)
  private String baseUrl;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "user_disabled", nullable = false)
  private boolean userDisabled;

  @Column(name = "requests_per_minute", nullable = false)
  private int requestsPerMinute;

  @Column(name = "requests_per_day", nullable = false)
  private int requestsPerDay;

  @Column(name = "respect_robots_txt", nullable = false)
  private boolean respectRobotsTxt;

  @Column(name = "user_agent", nullable = false, length = 160)
  private String userAgent;

  @Type(JsonBinaryType.class)
  @Column(name = "crawl_config", columnDefinition = "jsonb")
  private JsonNode crawlConfig;

  @Column(name = "failure_streak", nullable = false)
  private int failureStreak;

  @Column(name = "last_failure_at")
  private Instant lastFailureAt;

  @Column(name = "last_success_at")
  private Instant lastSuccessAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "quality_score", precision = 4, scale = 3)
  private BigDecimal qualityScore;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

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
