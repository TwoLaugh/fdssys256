package com.example.mealprep.household.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * Append-only audit row for a single field-path change on a {@link HouseholdSettings} document. One
 * row per genuine change per {@code PUT /settings}; no-op fields are skipped at write time.
 *
 * <p>No {@code @Version}, no {@code updatedAt}. JSONB columns map through {@link JsonBinaryType};
 * {@link JsonNode} keeps the per-field shape free-form (any value at any path). The (parent_id,
 * occurred_at DESC) index is created in V20260601500300.
 */
@Entity
@Table(name = "household_settings_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HouseholdSettingsAuditLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "household_settings_id", nullable = false, updatable = false)
  private UUID householdSettingsId;

  @Column(name = "actor_user_id", nullable = false, updatable = false)
  private UUID actorUserId;

  @Column(name = "field_path", nullable = false, updatable = false, length = 128)
  private String fieldPath;

  @Type(JsonBinaryType.class)
  @Column(
      name = "previous_value_json",
      nullable = false,
      updatable = false,
      columnDefinition = "jsonb")
  private JsonNode previousValueJson;

  @Type(JsonBinaryType.class)
  @Column(name = "new_value_json", nullable = false, updatable = false, columnDefinition = "jsonb")
  private JsonNode newValueJson;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;
}
