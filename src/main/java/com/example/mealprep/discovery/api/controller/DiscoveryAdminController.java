package com.example.mealprep.discovery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.api.dto.OrphanSweepResultDto;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
    currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
