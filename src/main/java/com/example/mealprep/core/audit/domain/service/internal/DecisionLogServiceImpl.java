package com.example.mealprep.core.audit.domain.service.internal;

import com.example.mealprep.core.audit.api.dto.AncestryResponse;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.api.mapper.DecisionLogMapper;
import com.example.mealprep.core.audit.domain.entity.DecisionLog;
import com.example.mealprep.core.audit.domain.repository.DecisionLogRepository;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
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

  public DecisionLogServiceImpl(DecisionLogRepository repository, DecisionLogMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  // ---------------- Write path ----------------

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID write(DecisionLogWriteRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    UUID decisionId = UUID.randomUUID();
    DecisionLog entity =
        new DecisionLog(
            decisionId,
            request.traceId(),
            request.parentDecisionId(),
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
