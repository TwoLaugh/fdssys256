package com.example.mealprep.core.audit.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Write DTO for {@link com.example.mealprep.core.audit.domain.service.DecisionLogService#write}.
 *
 * <p><b>Idempotency.</b> {@code decisionId} is <em>optional</em>: a caller that supplies a non-null
 * id gets idempotent writes — a retry reusing the same id returns the existing row without a second
 * insert (lld/core.md §Flow 1 step 3). A caller that leaves it {@code null} gets a fresh
 * service-generated id on every call (no idempotency). The 14-argument constructor (without {@code
 * decisionId}) is retained for callers that don't need idempotency and delegates with {@code
 * decisionId = null}.
 *
 * <p>All other fields are required unless explicitly nullable per the comments below.
 */
public record DecisionLogWriteRequest(
    UUID decisionId, // nullable — caller-supplied for idempotency; null → service generates one
    UUID traceId,
    UUID parentDecisionId, // nullable — null for trace roots
    String scopeKind,
    UUID scopeId,
    DecisionLogScale scale,
    String triggeredBy,
    UUID actorUserId, // nullable — null for system-initiated
    JsonNode inputs,
    JsonNode candidates, // nullable
    JsonNode chosen, // nullable
    String reasoning, // nullable
    JsonNode emittedDirective, // nullable
    int iteration,
    Integer durationMs // nullable
    ) {

  /**
   * Backward-compatible constructor for callers that do not supply a {@code decisionId} (the
   * service generates one). Delegates to the canonical constructor with {@code decisionId = null}.
   */
  public DecisionLogWriteRequest(
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
    this(
        null,
        traceId,
        parentDecisionId,
        scopeKind,
        scopeId,
        scale,
        triggeredBy,
        actorUserId,
        inputs,
        candidates,
        chosen,
        reasoning,
        emittedDirective,
        iteration,
        durationMs);
  }
}
