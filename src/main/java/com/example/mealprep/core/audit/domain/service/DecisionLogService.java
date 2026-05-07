package com.example.mealprep.core.audit.domain.service;

import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import java.util.UUID;

/**
 * Persists decision-log entries — one row per iteration of an optimisation loop, plus any other
 * audit-worthy decision points across the system.
 *
 * <p>Writes run in a {@code @Transactional(propagation = REQUIRES_NEW)} inner transaction so a log
 * row survives a caller's transaction rollback. This matches the AI call-log pattern from {@code
 * lld/ai.md}: audit must not be hostage to caller-side commit.
 *
 * @see DecisionLogQueryService for the read path
 */
public interface DecisionLogService {

  /**
   * Persists the decision-log entry. Generates and returns the {@code decisionId} application-side
   * via {@link UUID#randomUUID()} — never a DB default.
   *
   * @param request the entry to write; never null
   * @return the assigned {@code decisionId}
   */
  UUID write(DecisionLogWriteRequest request);
}
