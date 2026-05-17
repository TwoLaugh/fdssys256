package com.example.mealprep.discovery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.api.dto.OrphanSweepResultDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import com.example.mealprep.discovery.exception.DiscoveryAllSourcesUnavailableException;
import com.example.mealprep.discovery.exception.DiscoveryJobTimeoutException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST seam for the discovery module. Per ticket invariant 26.
 *
 * <p>{@code POST /admin/jobs/sync} is deliberately omitted in 01b — it depends on {@code
 * runJobSync} + CompletableFuture plumbing that ships with discovery-01f. The {@code
 * run-orphan-sweep} endpoint exists in 01b but the underlying logic is a placeholder (01d wires the
 * real sweep).
 */
@RestController
@RequestMapping("/api/v1/discovery/admin")
@Tag(name = "Discovery")
@Validated
public class DiscoveryAdminController {

  private final DiscoveryService discoveryService;
  private final CurrentUserResolver currentUserResolver;

  public DiscoveryAdminController(
      DiscoveryService discoveryService, CurrentUserResolver currentUserResolver) {
    this.discoveryService = discoveryService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(path = "/sources/{sourceKey}/enable", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Enable a discovery source (idempotent).")
  public DiscoverySourceDto enable(@PathVariable String sourceKey) {
    requireAuthenticated();
    return discoveryService.enableSource(sourceKey);
  }

  @PostMapping(path = "/sources/{sourceKey}/disable", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Disable a discovery source administratively. Does NOT set userDisabled — distinct"
              + " from the user-Settings toggle.")
  public DiscoverySourceDto disable(@PathVariable String sourceKey) {
    requireAuthenticated();
    return discoveryService.disableSource(sourceKey);
  }

  @PostMapping(
      path = "/jobs/sync",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Synchronously run a COLD_START discovery job; blocks up to timeoutSeconds (capped"
              + " server-side). Returns the terminal DTO on completion or the partial DTO on a"
              + " non-strict timeout. strictTimeout=true yields 408 on deadline expiry.")
  public ResponseEntity<DiscoveryJobDto> runJobSync(
      @Valid @RequestBody StartDiscoveryJobRequest request,
      @RequestParam(name = "timeoutSeconds", defaultValue = "60") @Min(1) @Max(300)
          int timeoutSeconds,
      @RequestParam(name = "strictTimeout", defaultValue = "false") boolean strictTimeout) {
    UUID userId = requireUserId();
    Duration timeout = Duration.ofSeconds(timeoutSeconds);
    DiscoveryJobDto result = discoveryService.runJobSync(userId, request, timeout);

    // Status → HTTP mapping (ticket invariant 14/15):
    //   SUCCEEDED / PARTIAL                 → 200 (terminal DTO)
    //   RUNNING + strictTimeout=true        → 408 (timeout; partial DTO conveyed via ProblemDetail)
    //   RUNNING + strictTimeout=false       → 200 (partial DTO acceptable per LLD line 543)
    //   FAILED + all sources down           → 502 (no source produced any success)
    //   FAILED + other                      → 200 (valid terminal; planner inspects the DTO)
    if (result.status() == DiscoveryJobStatus.RUNNING && strictTimeout) {
      throw new DiscoveryJobTimeoutException(result.id(), timeout);
    }
    if (result.status() == DiscoveryJobStatus.FAILED && allSourcesDown(result)) {
      throw new DiscoveryAllSourcesUnavailableException(result.id(), result.sourcesFailed());
    }
    return ResponseEntity.ok(result);
  }

  /**
   * All-sources-down heuristic per ticket invariant 15 (simplest v1): the job FAILED, at least one
   * source was attempted-and-failed, and not a single source succeeded. Approximate but workable —
   * the requested-source set may be null so we cannot compare against a resolved count here.
   */
  private static boolean allSourcesDown(DiscoveryJobDto dto) {
    boolean anyFailed = dto.sourcesFailed() != null && !dto.sourcesFailed().isEmpty();
    boolean noneSucceeded = dto.sourcesSucceeded() == null || dto.sourcesSucceeded().isEmpty();
    return anyFailed && noneSucceeded;
  }

  @PostMapping(path = "/run-orphan-sweep", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Run the orphan-sweep over RUNNING jobs with stale heartbeats. 01b returns 0; the"
              + " real implementation ships with discovery-01d.")
  public OrphanSweepResultDto runOrphanSweep() {
    requireAuthenticated();
    return discoveryService.runOrphanSweep();
  }

  private void requireAuthenticated() {
    requireUserId();
  }

  private UUID requireUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
