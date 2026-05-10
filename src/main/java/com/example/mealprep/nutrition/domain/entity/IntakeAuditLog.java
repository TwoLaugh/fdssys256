package com.example.mealprep.nutrition.domain.entity;

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
import org.hibernate.annotations.Type;

/**
 * Append-only audit row attached to an {@link IntakeDay}. One row per write action (confirm /
 * override / edit / skip / snack-add / snack-remove / pre-fill). {@code mealSlot} null for {@code
 * SNACK_*} actions; {@code snackId} populated for {@code SNACK_*}.
 *
 * <p>No {@code @Version}, no setters — once written, immutable.
 */
@Entity
@Table(name = "nutrition_intake_audit")
public class IntakeAuditLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "intake_day_id", nullable = false, updatable = false)
  private IntakeDay intakeDay;

  @Column(name = "actor_user_id", updatable = false, nullable = false)
  private UUID actorUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", updatable = false, nullable = false, length = 32)
  private IntakeAuditAction action;

  @Enumerated(EnumType.STRING)
  @Column(name = "meal_slot", updatable = false, length = 24)
  private MealSlot mealSlot;

  @Column(name = "snack_id", updatable = false)
  private UUID snackId;

  @Type(JsonBinaryType.class)
  @Column(name = "previous_value_json", updatable = false, columnDefinition = "jsonb")
  private JsonNode previousValueJson;

  @Type(JsonBinaryType.class)
  @Column(name = "new_value_json", updatable = false, columnDefinition = "jsonb")
  private JsonNode newValueJson;

  @Column(name = "occurred_at", updatable = false, nullable = false)
  private Instant occurredAt;

  /** For Hibernate. Not for application code. */
  protected IntakeAuditLog() {}

  public IntakeAuditLog(
      UUID id,
      IntakeDay intakeDay,
      UUID actorUserId,
      IntakeAuditAction action,
      MealSlot mealSlot,
      UUID snackId,
      JsonNode previousValueJson,
      JsonNode newValueJson,
      Instant occurredAt) {
    this.id = id;
    this.intakeDay = intakeDay;
    this.actorUserId = actorUserId;
    this.action = action;
    this.mealSlot = mealSlot;
    this.snackId = snackId;
    this.previousValueJson = previousValueJson;
    this.newValueJson = newValueJson;
    this.occurredAt = occurredAt;
  }

  public UUID getId() {
    return id;
  }

  public IntakeDay getIntakeDay() {
    return intakeDay;
  }

  public UUID getIntakeDayId() {
    return intakeDay == null ? null : intakeDay.getId();
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public IntakeAuditAction getAction() {
    return action;
  }

  public MealSlot getMealSlot() {
    return mealSlot;
  }

  public UUID getSnackId() {
    return snackId;
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
