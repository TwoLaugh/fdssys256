package com.example.mealprep.planner.api.controller;

import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.planner.api.dto.PlannerDecisionChainDto;
import com.example.mealprep.planner.api.mapper.PlannerDecisionMapper;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin read endpoint for a plan's decision-log chain (planner-01l, ticket invariant #10). Returns
 * the chain as a flat list ordered by {@code created_at}. Scoped to the {@code PLANNER} scope-kind
 * so cross-module rows (e.g. the adaptation pipeline's own {@code RECIPE}-scope rows that share a
 * {@code parent_decision_id} with a planner {@code STAGE_D_OUTCOME}) are not mixed in here.
 *
 * <p>Plans generated BEFORE planner-01l shipped have no decision-log rows — the endpoint returns an
 * empty {@code rows} list (no retroactive backfill; ticket invariant #12).
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} mirrors {@code
 * core.audit.AdminDecisionLogController} (the ticket's literal {@code hasRole('ROLE_ADMIN')} would
 * wrongly require a {@code ROLE_ROLE_ADMIN} authority — Spring's {@code hasRole} already prepends
 * the {@code ROLE_} prefix). As in core, the flat v1 user model has no admin authority yet, so this
 * currently gates anonymous (401) but admits any authenticated user — tightened when the
 * household-admin role lands (TODO auth-roles-followup).
 */
@RestController
@RequestMapping("/api/v1/admin/planner")
@Tag(
    name = "AdminPlannerDecisions",
    description = "Admin observability for a plan's decision-log chain.")
public class AdminPlannerDecisionsController {

  private final DecisionLogQueryService queryService;
  private final PlannerDecisionMapper mapper;

  public AdminPlannerDecisionsController(
      DecisionLogQueryService queryService, PlannerDecisionMapper mapper) {
    this.queryService = queryService;
    this.mapper = mapper;
  }

  @GetMapping("/decisions/{planId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Get a plan's decision-log chain (flat, created_at order).",
      description =
          "Returns every PLANNER-scope decision row for the plan as a single connected DAG. "
              + "Optional traceId filter narrows to one generation/re-opt trace. Plans generated "
              + "before planner-01l shipped have no rows (empty list; no retroactive backfill).")
  public ResponseEntity<PlannerDecisionChainDto> getChain(
      @PathVariable UUID planId, @RequestParam(required = false) @Nullable UUID traceId) {
    List<DecisionLogDto> rows;
    if (traceId != null) {
      // ?traceId= alternative: the trace's rows, narrowed to this plan's PLANNER-scope rows so a
      // shared cross-module trace doesn't leak adaptation RECIPE-scope rows into the plan view.
      rows =
          queryService.getByTraceId(traceId).stream()
              .filter(r -> DecisionLogWriter.SCOPE_KIND.equals(r.scopeKind()))
              .filter(r -> planId.equals(r.scopeId()))
              .toList();
    } else {
      rows = queryService.getByScope(DecisionLogWriter.SCOPE_KIND, planId);
    }
    return ResponseEntity.ok(new PlannerDecisionChainDto(planId, mapper.toRows(rows)));
  }
}
