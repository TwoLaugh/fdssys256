package com.example.mealprep.core.audit.domain.service.internal;

import com.example.mealprep.core.audit.api.dto.AncestryResponse;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.api.mapper.DecisionLogMapper;
import com.example.mealprep.core.audit.domain.entity.DecisionLog;
import com.example.mealprep.core.audit.domain.repository.DecisionLogRepository;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.core.exception.DecisionLogNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of both {@link DecisionLogService} (write) and {@link
 * DecisionLogQueryService} (read). Reads are read-only-transactional; writes run in {@code
 * REQUIRES_NEW} so the audit row survives a caller's transaction rollback.
 */
@Service
public class DecisionLogServiceImpl implements DecisionLogService, DecisionLogQueryService {

  /** Hard upper bound on ancestry depth. Defends against malformed cycles. */
  public static final int ANCESTRY_DEPTH_CAP = 32;

  private final DecisionLogRepository repository;
  private final DecisionLogMapper mapper;
  private final DecisionLogTokenBudgetGuard tokenBudgetGuard;

  public DecisionLogServiceImpl(
      DecisionLogRepository repository,
      DecisionLogMapper mapper,
      DecisionLogTokenBudgetGuard tokenBudgetGuard) {
    this.repository = repository;
    this.mapper = mapper;
    this.tokenBudgetGuard = tokenBudgetGuard;
  }

  // ---------------- Write path ----------------

  /**
   * Persist a decision-log entry. {@code @Transactional(REQUIRES_NEW)} so the row commits in its
   * own inner transaction and survives a caller-side rollback (records what was attempted). Per
   * lld/core.md §Flow 1:
   *
   * <ol>
   *   <li>Null-request guard.
   *   <li>Payload size guard (≤ 64 KB) → {@code DecisionLogPayloadOversizedException} (422).
   *   <li>Idempotency: a non-null caller-supplied {@code decisionId} that already exists
   *       short-circuits and returns that id (no second insert). A null id is generated fresh.
   *   <li>Parent existence: a non-null {@code parentDecisionId} that does not resolve → {@code
   *       DecisionLogNotFoundException} (404).
   *   <li>Persist; return the id.
   * </ol>
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID write(DecisionLogWriteRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    // Size guard before any DB work — a runaway payload should fail fast.
    tokenBudgetGuard.assertWithinBudget(request);

    // Idempotency: reuse a caller-supplied id when it already exists; generate one otherwise.
    UUID decisionId = request.decisionId();
    if (decisionId != null && repository.existsById(decisionId)) {
      return decisionId;
    }
    if (decisionId == null) {
      decisionId = UUID.randomUUID();
    }

    // Parent-existence check: clearer upfront 404 than the deferred self-FK violation.
    UUID parentDecisionId = request.parentDecisionId();
    if (parentDecisionId != null && !repository.existsById(parentDecisionId)) {
      throw new DecisionLogNotFoundException(parentDecisionId);
    }

    DecisionLog entity =
        new DecisionLog(
            decisionId,
            request.traceId(),
            parentDecisionId,
            request.scopeKind(),
            request.scopeId(),
            request.scale(),
            request.triggeredBy(),
            request.actorUserId(),
            request.inputs(),
            request.candidates(),
            request.chosen(),
            request.reasoning(),
            request.emittedDirective(),
            request.iteration(),
            request.durationMs());
    repository.save(entity);
    return decisionId;
  }

  // ---------------- Read path ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<DecisionLogDto> getById(UUID decisionId) {
    return repository.findById(decisionId).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DecisionLogDto> getByTraceId(UUID traceId) {
    return mapper.toDtos(repository.findByTraceIdOrderByCreatedAtAsc(traceId));
  }

  @Override
  @Transactional(readOnly = true)
  public List<DecisionLogDto> getByScope(String scopeKind, UUID scopeId) {
    return mapper.toDtos(
        repository.findByScopeKindAndScopeIdOrderByCreatedAtAsc(scopeKind, scopeId));
  }

  @Override
  @Transactional(readOnly = true)
  public AncestryResponse getAncestry(UUID decisionId, int maxDepth) {
    int clamped = Math.max(1, Math.min(ANCESTRY_DEPTH_CAP, maxDepth));
    List<DecisionLog> ancestors = repository.findAncestry(decisionId, clamped);
    List<DecisionLogDto> dtos = mapper.toDtos(ancestors);
    // Cycle / depth-cap heuristic: if we got back exactly the cap, the walk hit the limit.
    // True cycles produce maxDepth rows; legitimately-deep traces also do — flag and let
    // the caller investigate.
    boolean cycleDetected = dtos.size() == clamped && clamped == ANCESTRY_DEPTH_CAP;
    return new AncestryResponse(dtos, cycleDetected);
  }
}
