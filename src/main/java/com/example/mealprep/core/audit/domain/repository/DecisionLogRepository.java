package com.example.mealprep.core.audit.domain.repository;

import com.example.mealprep.core.audit.domain.entity.DecisionLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DecisionLog}. Package-private at the package level (this class
 * is public so Spring can resolve it across packages, but consumers outside the {@code core.audit}
 * package go through {@code DecisionLogService}/{@code DecisionLogQueryService} per the
 * cross-module rules).
 */
public interface DecisionLogRepository extends JpaRepository<DecisionLog, UUID> {

  /**
   * Fetch all entries sharing a trace, ordered earliest-first by {@code created_at}. Uses {@code
   * idx_decision_log_trace_created}.
   */
  List<DecisionLog> findByTraceIdOrderByCreatedAtAsc(UUID traceId);

  /**
   * Walk {@code parent_decision_id} recursively starting from {@code decisionId}'s parent, up to
   * {@code maxDepth} levels. Returns ancestors only; the input row itself is not included. Order:
   * root-first (deepest ancestor at index 0).
   *
   * <p>The depth cap defends against malformed cycles in {@code parent_decision_id}. When the cap
   * is hit, {@code DecisionLogQueryService} marks the response with {@code cycleDetected = true}.
   */
  @Query(
      value =
          """
          WITH RECURSIVE ancestors AS (
            SELECT dl.*, 1 AS depth
              FROM decision_log dl
             WHERE dl.decision_id = (
                     SELECT parent_decision_id FROM decision_log WHERE decision_id = :decisionId
                   )
             UNION ALL
            SELECT dl.*, a.depth + 1
              FROM decision_log dl
              JOIN ancestors a ON dl.decision_id = a.parent_decision_id
             WHERE a.depth < :maxDepth
          )
          SELECT decision_id, trace_id, parent_decision_id, scope_kind, scope_id, scale,
                 triggered_by, actor_user_id, inputs, candidates, chosen, reasoning,
                 emitted_directive, iteration, duration_ms, created_at
            FROM ancestors
            ORDER BY depth DESC
          """,
      nativeQuery = true)
  List<DecisionLog> findAncestry(
      @Param("decisionId") UUID decisionId, @Param("maxDepth") int maxDepth);
}
