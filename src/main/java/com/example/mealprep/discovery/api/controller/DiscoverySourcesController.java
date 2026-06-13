package com.example.mealprep.discovery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.domain.service.DiscoveryQueryService;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import com.example.mealprep.discovery.exception.DiscoverySourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the discovery-source registry: the read surface (ticket invariant 25) plus the
 * user-facing {@code user-disable} / {@code user-enable} verb pair (ticket
 * discovery-user-source-disable). These are <em>user</em> endpoints — any authenticated user, NOT
 * under {@code /discovery/admin/**}; the admin {@code enabled} verbs live on {@code
 * DiscoveryAdminController}.
 *
 * <p>{@link CurrentUserResolver} only gates the request behind authentication; source data isn't
 * user-scoped (single-user semantics in v1 — {@code user_disabled} is a column on the global source
 * row).
 */
@RestController
@RequestMapping("/api/v1/discovery/sources")
@Tag(name = "Discovery")
public class DiscoverySourcesController {

  private final DiscoveryQueryService discoveryQueryService;
  private final DiscoveryService discoveryService;
  private final CurrentUserResolver currentUserResolver;

  public DiscoverySourcesController(
      DiscoveryQueryService discoveryQueryService,
      DiscoveryService discoveryService,
      CurrentUserResolver currentUserResolver) {
    this.discoveryQueryService = discoveryQueryService;
    this.discoveryService = discoveryService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List all discovery sources (sorted by displayName).")
  @BoundedCollection("static registry; bounded by configured source count")
  public List<DiscoverySourceDto> list() {
    requireAuthenticated();
    return discoveryQueryService.listSources();
  }

  @GetMapping(path = "/{sourceKey}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch a discovery source by its stable key.")
  public DiscoverySourceDto getByKey(@PathVariable String sourceKey) {
    requireAuthenticated();
    return discoveryQueryService
        .getSource(sourceKey)
        .orElseThrow(() -> new DiscoverySourceNotFoundException(sourceKey));
  }

  @PostMapping(path = "/{sourceKey}/user-disable", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "User: disable a discovery source (idempotent). Sets userDisabled only — the admin"
              + " enabled flag is untouched. The source is excluded from job-source resolution"
              + " (effective availability = enabled && !userDisabled).")
  public DiscoverySourceDto userDisable(@PathVariable String sourceKey) {
    requireAuthenticated();
    return discoveryService.userDisableSource(sourceKey);
  }

  @PostMapping(path = "/{sourceKey}/user-enable", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "User: re-enable a discovery source (idempotent). Clears userDisabled only — an"
              + " admin-disabled source stays unavailable (enabled wins).")
  public DiscoverySourceDto userEnable(@PathVariable String sourceKey) {
    requireAuthenticated();
    return discoveryService.userEnableSource(sourceKey);
  }

  private void requireAuthenticated() {
    currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
