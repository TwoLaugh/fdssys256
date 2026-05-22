package com.example.mealprep.notification.domain.entity;

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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

/**
 * Aggregate root for a user-visible notification. One row per surfaced notification; a row may
 * absorb siblings via the debouncer ({@code bundleCount > 1}). {@code title}/{@code body} are
 * denormalised so the inbox endpoint never re-renders copy from {@link #payload}.
 *
 * <p>{@code payload} is a sealed {@link NotificationPayload} tree mapped through hypersistence
 * {@link JsonBinaryType}; {@code bundleKeys} is a free-form JSONB array of the bundled origin keys.
 * {@code householdId} is populated for household-scoped kinds and null for per-user kinds.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Notification {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "household_id", updatable = false)
  private UUID householdId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 64)
  private NotificationKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16)
  private NotificationSeverity severity;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  @Column(name = "body", nullable = false, length = 1000)
  private String body;

  @Type(JsonBinaryType.class)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private NotificationPayload payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private NotificationStatus status;

  @Column(name = "action_target_uri", length = 512)
  private String actionTargetUri;

  @Column(name = "bundle_count", nullable = false)
  private int bundleCount;

  @Type(JsonBinaryType.class)
  @Column(name = "bundle_keys", columnDefinition = "jsonb")
  private JsonNode bundleKeys;

  @Column(name = "source_event_id", updatable = false)
  private UUID sourceEventId;

  @Column(name = "trace_id", updatable = false)
  private UUID traceId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "actioned_at")
  private Instant actionedAt;

  @Column(name = "dismissed_at")
  private Instant dismissedAt;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;
}
