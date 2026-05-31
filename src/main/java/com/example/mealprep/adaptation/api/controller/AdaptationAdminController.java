package com.example.mealprep.adaptation.api.controller;

import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.api.dto.RetryFailedJobRequest;
import com.example.mealprep.adaptation.api.dto.SweepExpiredPendingResponse;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.AdaptationJobNotFoundException;
import com.example.mealprep.adaptation.exception.AdaptationTraceNotFoundException;
import com.example.mealprep.auth.api.AdminAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin / debug surface for the adaptation pipeline. Seven endpoints, all gated to {@code
 * ROLE_ADMIN} per LLD line 578.
 *
 * <p><b>Authorisation.</b> {@code @PreAuthorize("hasRole('ROLE_ADMIN')")} is the published contract
 * but inert in v1 (the project does not enable method-security). Enforcement is done imperatively
 * via the shared {@link AdminAccessGuard#requireAdmin()} (config key {@code
 * mealprep.admin.user-ids}) at the top of every handler: anonymous ⇒ 401 (also enforced by the
 * deny-by-default security chain), authenticated-but-not-allowlisted ⇒ 403, fail-closed on an empty
 * allowlist. Mirrors {@code AdminDecisionLogController}.
 *
 * <p>Per ticket 01f §AdaptationAdminController and {@code lld/adaptation-pipeline.md} lines
 * 599-609.
 */
@RestController
@RequestMapping("/api/v1/adaptation")
@Validated
@Tag(name = "Adaptation")
public class AdaptationAdminController {

  private final AdaptationService adaptationService;
  private final AdaptationQueryService queryService;
  private final AdminAccessGuard adminGuard;

  public AdaptationAdminController(
      AdaptationService adaptationService,
      AdaptationQueryService queryService,
      AdminAccessGuard adminGuard) {
    this.adaptationService = adaptationService;
    this.queryService = queryService;
    this.adminGuard = adminGuard;
  }

  @GetMapping(path = "/jobs/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: fetch a job by id.")
  public AdaptationJobDto getJob(@PathVariable("jobId") UUID jobId) {
    adminGuard.requireAdmin();
    return queryService
        .getJob(jobId)
        .orElseThrow(
            () -> new AdaptationJobNotFoundException("adaptation job not found: " + jobId));
  }

  @GetMapping(path = "/jobs/{jobId}/trace", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: fetch the trace for a job.")
  public AdaptationTraceDto getJobTrace(@PathVariable("jobId") UUID jobId) {
    adminGuard.requireAdmin();
    return queryService
        .getTraceForJob(jobId)
        .orElseThrow(() -> new AdaptationTraceNotFoundException("no trace for job: " + jobId));
  }

  @GetMapping(path = "/recipes/{recipeId}/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: page of jobs for a recipe, newest first.")
  public Page<AdaptationJobDto> getRecipeJobs(
      @PathVariable("recipeId") UUID recipeId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    adminGuard.requireAdmin();
    return queryService.getJobsForRecipe(recipeId, PageRequest.of(page, size));
  }

  @GetMapping(path = "/recipes/{recipeId}/traces", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: page of traces for a recipe, newest first.")
  public Page<AdaptationTraceDto> getRecipeTraces(
      @PathVariable("recipeId") UUID recipeId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    adminGuard.requireAdmin();
    return queryService.getTracesForRecipe(recipeId, PageRequest.of(page, size));
  }

  @PostMapping(path = "/admin/sweep-expired-pending", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: ad-hoc invoke the expired-pending-change sweep.")
  public SweepExpiredPendingResponse sweepExpiredPending() {
    adminGuard.requireAdmin();
    return new SweepExpiredPendingResponse(adaptationService.sweepExpiredPendingChanges());
  }

  @PostMapping(
      path = "/admin/retry-failed-job",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: re-enqueue a fresh copy of a FAILED job.")
  public AdaptationJobDto retryFailedJob(@Valid @RequestBody RetryFailedJobRequest body) {
    adminGuard.requireAdmin();
    return adaptationService.retryFailedJob(body.jobId());
  }

  @GetMapping(
      path = "/admin/prompt-versions/{name}/{version}/traces",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: page of traces filtered by prompt-template name + version.")
  public Page<AdaptationTraceDto> getPromptVersionTraces(
      @PathVariable("name") String name,
      @PathVariable("version") String version,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    adminGuard.requireAdmin();
    return queryService.getTracesForPromptVersion(name, version, PageRequest.of(page, size));
  }
}
