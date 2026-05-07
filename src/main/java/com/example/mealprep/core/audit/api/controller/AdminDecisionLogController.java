package com.example.mealprep.core.audit.api.controller;

import com.example.mealprep.core.audit.api.dto.AncestryResponse;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.core.audit.domain.service.internal.DecisionLogServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin observability endpoints for the decision log.
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is present on every method but is not yet enforced —
 * the auth-01 ticket activates Spring Security's method-security pipeline.
 * <strong>TODO(auth-01-followup):</strong> verify these endpoints reject anonymous access once the
 * auth filter chain is wired.
 */
@RestController
@RequestMapping("/api/v1/admin/decision-log")
@Validated
@Tag(
    name = "DecisionLog",
    description = "Admin observability for the optimisation-loop decision log.")
public class AdminDecisionLogController {

  private final DecisionLogQueryService queryService;

  public AdminDecisionLogController(DecisionLogQueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping("/{decisionId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Get a single decision-log entry by ID.")
  public ResponseEntity<DecisionLogDto> getById(@PathVariable UUID decisionId) {
    return queryService
        .getById(decisionId)
        .map(ResponseEntity::ok)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Decision log entry not found: " + decisionId));
  }

  @GetMapping("/trace/{traceId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Get all decision-log entries for a trace, ordered by creation time.")
  public List<DecisionLogDto> getByTraceId(@PathVariable UUID traceId) {
    return queryService.getByTraceId(traceId);
  }

  @GetMapping("/{decisionId}/ancestry")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Walk parent_decision_id recursively up to maxDepth (1..32).",
      description =
          "Returns ancestors root-first; the input decisionId itself is excluded. "
              + "cycleDetected is true when the walk hit the depth cap, indicating a "
              + "potentially-malformed parent chain.")
  public AncestryResponse getAncestry(
      @PathVariable UUID decisionId,
      @RequestParam(defaultValue = "32") @Min(1) @Max(DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP)
          int maxDepth) {
    return queryService.getAncestry(decisionId, maxDepth);
  }
}
