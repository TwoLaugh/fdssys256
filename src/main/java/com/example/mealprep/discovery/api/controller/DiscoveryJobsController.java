package com.example.mealprep.discovery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.DiscoveryScrapeLogEntryDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.service.DiscoveryQueryService;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import com.example.mealprep.discovery.exception.DiscoveryJobNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the discovery-job aggregate. {@code userId} resolved server-side via {@link
 * CurrentUserResolver}. Authentication is enforced by the auth module's deny-by-default chain;
 * anonymous callers receive 401 from the filter before this controller runs.
 *
 * <p>Per ticket invariant 24.
 */
@RestController
@RequestMapping("/api/v1/discovery/jobs")
@Tag(name = "Discovery")
@Validated
public class DiscoveryJobsController {

  private final DiscoveryService discoveryService;
  private final DiscoveryQueryService discoveryQueryService;
  private final CurrentUserResolver currentUserResolver;

  public DiscoveryJobsController(
      DiscoveryService discoveryService,
      DiscoveryQueryService discoveryQueryService,
      CurrentUserResolver currentUserResolver) {
    this.discoveryService = discoveryService;
    this.discoveryQueryService = discoveryQueryService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Enqueue a discovery job; returns the queued DTO. The async runner picks it up via"
              + " DiscoveryJobStartedEvent.")
  public ResponseEntity<DiscoveryJobDto> start(
      @Valid @RequestBody StartDiscoveryJobRequest request) {
    UUID userId = requireCurrentUserId();
    DiscoveryJobDto created = discoveryService.startJob(userId, request);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(URI.create("/api/v1/discovery/jobs/" + created.id()))
        .body(created);
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated discovery-job history for the current user, queued-at desc.")
  public Page<DiscoveryJobDto> list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return discoveryQueryService.listJobsForUser(userId, pageable);
  }

  @GetMapping(path = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch a discovery job by id (404 if not owned by the caller).")
  public DiscoveryJobDto getById(@PathVariable UUID jobId) {
    UUID userId = requireCurrentUserId();
    return discoveryQueryService
        .getJobForUser(userId, jobId)
        .orElseThrow(() -> new DiscoveryJobNotFoundException(jobId));
  }

  @PostMapping(path = "/{jobId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Cancel a queued discovery job. Returns the updated DTO. 422 for terminal /"
              + " in-flight states (01b limitation).")
  public DiscoveryJobDto cancel(@PathVariable UUID jobId) {
    UUID userId = requireCurrentUserId();
    discoveryService.cancelJob(userId, jobId);
    return discoveryQueryService
        .getJobForUser(userId, jobId)
        .orElseThrow(() -> new DiscoveryJobNotFoundException(jobId));
  }

  @GetMapping(path = "/{jobId}/scrape-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Page of scrape-log rows for a job. 404 if the job is unknown.")
  public Page<DiscoveryScrapeLogEntryDto> scrapeLog(
      @PathVariable UUID jobId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return discoveryQueryService.getScrapeLog(jobId, pageable);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
