package com.example.mealprep.core.audit.domain.service;

import com.example.mealprep.core.audit.api.dto.AncestryResponse;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to the decision-log table. Widely injected; admin-tier endpoints in {@link
 * com.example.mealprep.core.audit.api.controller.AdminDecisionLogController} call through this
 * interface, as do downstream consumers that walk decision traces.
 */
public interface DecisionLogQueryService {

  /** Fetch a single entry by its decision id. Empty if not found. */
  Optional<DecisionLogDto> getById(UUID decisionId);

  /**
   * Fetch all entries sharing a {@code traceId}, ordered by {@code createdAt} ascending. Returns an
   * empty list — never null — when no entries match.
   */
  List<DecisionLogDto> getByTraceId(UUID traceId);

  /**
   * Fetch all entries for a {@code (scopeKind, scopeId)} pair, ordered by {@code createdAt}
   * ascending. Backs scope-scoped admin reads (e.g. the planner's "decisions about this plan"
   * endpoint). Uses {@code idx_decision_log_scope_created}. Returns an empty list — never null —
   * when no entries match (e.g. a plan generated before the writing module shipped).
   */
  List<DecisionLogDto> getByScope(String scopeKind, UUID scopeId);

  /**
   * Walk {@code parentDecisionId} recursively up to {@code maxDepth} levels. The returned {@link
   * AncestryResponse} carries the chain root-first plus a {@code cycleDetected} flag set when the
   * depth cap was reached (likely indicating a malformed cycle).
   *
   * @param decisionId the leaf to start walking from
   * @param maxDepth max ancestor levels to return; clamped to 1..32 inclusive
   * @return the ancestry chain; never null. Empty {@code ancestors} list if {@code decisionId}
   *     itself is not found.
   */
  AncestryResponse getAncestry(UUID decisionId, int maxDepth);
}
