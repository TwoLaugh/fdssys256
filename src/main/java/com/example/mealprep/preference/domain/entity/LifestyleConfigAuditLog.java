package com.example.mealprep.preference.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Append-only audit row for one top-level section change on a {@link LifestyleConfig}. Written
 * inside the same {@code @Transactional} boundary as the parent update; no-op sections do not
 * produce rows.
 *
 * <p>{@code fieldPath} is the section name (e.g. {@code "batchCooking"}); sub-section paths ({@code
 * "batchCooking.prepDays"}) are reserved for a future finer-grained diff but the v1 implementation
 * only writes section-level rows.
 *
 * <p>No {@code @Version}, no {@code @LastModifiedDate} — append-only.
 */
@Entity
@Table(name = "preference_lifestyle_config_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LifestyleConfigAuditLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lifestyle_config_id", nullable = false, updatable = false)
  private LifestyleConfig lifestyleConfig;

  @Column(name = "actor_user_id", updatable = false, nullable = false)
  private UUID actorUserId;

  @Column(name = "field_path", updatable = false, nullable = false, length = 128)
  private String fieldPath;

  @Type(JsonBinaryType.class)
  @Column(
      name = "previous_value_json",
      updatable = false,
      nullable = false,
      columnDefinition = "jsonb")
  private JsonNode previousValueJson;

  @Type(JsonBinaryType.class)
  @Column(name = "new_value_json", updatable = false, nullable = false, columnDefinition = "jsonb")
  private JsonNode newValueJson;

  @Column(name = "occurred_at", updatable = false, nullable = false)
  private Instant occurredAt;
}
