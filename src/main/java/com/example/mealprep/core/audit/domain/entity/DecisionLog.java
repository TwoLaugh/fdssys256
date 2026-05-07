package com.example.mealprep.core.audit.domain.entity;

import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
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
import org.springframework.data.annotation.CreatedDate;

/**
 * JPA entity for the {@code decision_log} table. Append-only — no {@code @Version}, no {@code
 * updated_at}, no setters exposed for any mutable update path.
 *
 * <p>JSONB columns map through {@link JsonBinaryType} from hypersistence-utils-hibernate-63.
 * Callers see {@link JsonNode} so the per-scope shape stays in domain modules.
 *
 * <p>Constructed via the all-args constructor; Hibernate uses the no-args constructor (protected;
 * not for application code).
 */
@Entity
@Table(name = "decision_log")
public class DecisionLog {

  @Id
  @Column(name = "decision_id", updatable = false, nullable = false)
  private UUID decisionId;

  @Column(name = "trace_id", updatable = false, nullable = false)
  private UUID traceId;

  @Column(name = "parent_decision_id", updatable = false)
  private UUID parentDecisionId;

  @Column(name = "scope_kind", updatable = false, nullable = false, length = 32)
  private String scopeKind;

  @Column(name = "scope_id", updatable = false, nullable = false)
  private UUID scopeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scale", updatable = false, nullable = false, length = 16)
  private DecisionLogScale scale;

  @Column(name = "triggered_by", updatable = false, nullable = false, length = 32)
  private String triggeredBy;

  @Column(name = "actor_user_id", updatable = false)
  private UUID actorUserId;

  @Type(JsonBinaryType.class)
  @Column(name = "inputs", updatable = false, nullable = false, columnDefinition = "jsonb")
  private JsonNode inputs;

  @Type(JsonBinaryType.class)
  @Column(name = "candidates", updatable = false, columnDefinition = "jsonb")
  private JsonNode candidates;

  @Type(JsonBinaryType.class)
  @Column(name = "chosen", updatable = false, columnDefinition = "jsonb")
  private JsonNode chosen;

  @Column(name = "reasoning", updatable = false)
  private String reasoning;

  @Type(JsonBinaryType.class)
  @Column(name = "emitted_directive", updatable = false, columnDefinition = "jsonb")
  private JsonNode emittedDirective;

  @Column(name = "iteration", updatable = false, nullable = false)
  private int iteration;

  @Column(name = "duration_ms", updatable = false)
  private Integer durationMs;

  @CreatedDate
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  /** For Hibernate. Not for application code. */
  protected DecisionLog() {}

  public DecisionLog(
      UUID decisionId,
      UUID traceId,
      UUID parentDecisionId,
      String scopeKind,
      UUID scopeId,
      DecisionLogScale scale,
      String triggeredBy,
      UUID actorUserId,
      JsonNode inputs,
      JsonNode candidates,
      JsonNode chosen,
      String reasoning,
      JsonNode emittedDirective,
      int iteration,
      Integer durationMs) {
    this.decisionId = decisionId;
    this.traceId = traceId;
    this.parentDecisionId = parentDecisionId;
    this.scopeKind = scopeKind;
    this.scopeId = scopeId;
    this.scale = scale;
    this.triggeredBy = triggeredBy;
    this.actorUserId = actorUserId;
    this.inputs = inputs;
    this.candidates = candidates;
    this.chosen = chosen;
    this.reasoning = reasoning;
    this.emittedDirective = emittedDirective;
    this.iteration = iteration;
    this.durationMs = durationMs;
  }

  public UUID getDecisionId() {
    return decisionId;
  }

  public UUID getTraceId() {
    return traceId;
  }

  public UUID getParentDecisionId() {
    return parentDecisionId;
  }

  public String getScopeKind() {
    return scopeKind;
  }

  public UUID getScopeId() {
    return scopeId;
  }

  public DecisionLogScale getScale() {
    return scale;
  }

  public String getTriggeredBy() {
    return triggeredBy;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public JsonNode getInputs() {
    return inputs;
  }

  public JsonNode getCandidates() {
    return candidates;
  }

  public JsonNode getChosen() {
    return chosen;
  }

  public String getReasoning() {
    return reasoning;
  }

  public JsonNode getEmittedDirective() {
    return emittedDirective;
  }

  public int getIteration() {
    return iteration;
  }

  public Integer getDurationMs() {
    return durationMs;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
