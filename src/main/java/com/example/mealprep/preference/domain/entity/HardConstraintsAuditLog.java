package com.example.mealprep.preference.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * Append-only audit row for a single field change on the hard-constraints aggregate. One row per
 * {@code fieldChanged} per update; no-op fields are skipped at write time.
 *
 * <p>No {@code @Version}, no {@code updated_at}, no setters — once written, immutable. JSONB
 * columns map through {@link JsonBinaryType}; callers see {@link JsonNode} so the per-field shape
 * stays in domain modules.
 */
@Entity
@Table(name = "preference_hard_constraints_audit")
public class HardConstraintsAuditLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "hard_constraints_id", updatable = false, nullable = false)
  private UUID hardConstraintsId;

  @Column(name = "actor_user_id", updatable = false, nullable = false)
  private UUID actorUserId;

  @Column(name = "field_changed", updatable = false, nullable = false, length = 64)
  private String fieldChanged;

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

  /** For Hibernate. Not for application code. */
  protected HardConstraintsAuditLog() {}

  public HardConstraintsAuditLog(
      UUID id,
      UUID hardConstraintsId,
      UUID actorUserId,
      String fieldChanged,
      JsonNode previousValueJson,
      JsonNode newValueJson,
      Instant occurredAt) {
    this.id = id;
    this.hardConstraintsId = hardConstraintsId;
    this.actorUserId = actorUserId;
    this.fieldChanged = fieldChanged;
    this.previousValueJson = previousValueJson;
    this.newValueJson = newValueJson;
    this.occurredAt = occurredAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getHardConstraintsId() {
    return hardConstraintsId;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public String getFieldChanged() {
    return fieldChanged;
  }

  public JsonNode getPreviousValueJson() {
    return previousValueJson;
  }

  public JsonNode getNewValueJson() {
    return newValueJson;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
