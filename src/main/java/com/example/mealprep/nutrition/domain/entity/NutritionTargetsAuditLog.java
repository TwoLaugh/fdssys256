package com.example.mealprep.nutrition.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * Append-only audit row for a single field change on a {@link NutritionTargets} aggregate. One row
 * per {@code fieldPath} per update; no-op fields are skipped at write time.
 *
 * <p>{@code actorKind} discriminates 01a's USER writes from later sub-tickets' HEALTH_DIRECTIVE
 * (01e) and FEEDBACK rows. {@code sourceDirectiveId} is null for USER writes.
 *
 * <p>No {@code @Version}, no {@code updated_at}, no setters — once written, immutable.
 */
@Entity
@Table(name = "nutrition_targets_audit")
public class NutritionTargetsAuditLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "targets_id", updatable = false, nullable = false)
  private UUID targetsId;

  @Column(name = "actor_user_id", updatable = false, nullable = false)
  private UUID actorUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_kind", updatable = false, nullable = false, length = 24)
  private ActorKind actorKind;

  @Column(name = "source_directive_id", updatable = false)
  private UUID sourceDirectiveId;

  @Column(name = "field_path", updatable = false, nullable = false, length = 96)
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

  /** For Hibernate. Not for application code. */
  protected NutritionTargetsAuditLog() {}

  public NutritionTargetsAuditLog(
      UUID id,
      UUID targetsId,
      UUID actorUserId,
      ActorKind actorKind,
      UUID sourceDirectiveId,
      String fieldPath,
      JsonNode previousValueJson,
      JsonNode newValueJson,
      Instant occurredAt) {
    this.id = id;
    this.targetsId = targetsId;
    this.actorUserId = actorUserId;
    this.actorKind = actorKind;
    this.sourceDirectiveId = sourceDirectiveId;
    this.fieldPath = fieldPath;
    this.previousValueJson = previousValueJson;
    this.newValueJson = newValueJson;
    this.occurredAt = occurredAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTargetsId() {
    return targetsId;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public ActorKind getActorKind() {
    return actorKind;
  }

  public UUID getSourceDirectiveId() {
    return sourceDirectiveId;
  }

  public String getFieldPath() {
    return fieldPath;
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
