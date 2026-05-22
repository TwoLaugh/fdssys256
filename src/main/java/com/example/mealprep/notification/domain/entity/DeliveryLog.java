package com.example.mealprep.notification.domain.entity;

import com.example.mealprep.notification.domain.service.internal.delivery.DeliveryChannel;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Append-only audit row for one delivery attempt against one channel — including structured skips
 * (preference-off, quiet-hours, deduped-into-bundle). No {@code @Version}, no {@code updated_at}:
 * rows are never mutated. {@code @ManyToOne(LAZY)} back to {@link Notification} so the
 * cascade-delete on the parent table reaches these rows.
 */
@Entity
@Table(name = "notification_delivery_log")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DeliveryLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "notification_id", nullable = false, updatable = false)
  private Notification notification;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false, updatable = false, length = 32)
  private DeliveryChannel.Channel channel;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, updatable = false, length = 16)
  private DeliveryOutcome outcome;

  @Enumerated(EnumType.STRING)
  @Column(name = "skip_reason", updatable = false, length = 64)
  private DeliverySkipReason skipReason;

  @Type(JsonBinaryType.class)
  @Column(name = "detail", updatable = false, columnDefinition = "jsonb")
  private JsonNode detail;

  @Column(name = "attempted_at", nullable = false, updatable = false)
  private Instant attemptedAt;
}
