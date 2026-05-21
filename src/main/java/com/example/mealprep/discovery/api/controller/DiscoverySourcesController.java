package com.example.mealprep.discovery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.domain.service.DiscoveryQueryService;
import com.example.mealprep.discovery.exception.DiscoverySourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only REST seam for the discovery-source registry. Per ticket invariant 25.
 *
 * <p>{@link CurrentUserResolver} only gates the request behind authentication; source data isn't
 * user-scoped.
 */
@RestController
@RequestMapping("/api/v1/discovery/sources")
@Tag(name = "Discovery")
public class DiscoverySourcesController {

  private final DiscoveryQueryService discoveryQueryService;
  private final CurrentUserResolver currentUserResolver;

  public DiscoverySourcesController(
      DiscoveryQueryService discoveryQueryService, CurrentUserResolver currentUserResolver) {
    this.discoveryQueryService = discoveryQueryService;
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

  private void requireAuthenticated() {
    currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
