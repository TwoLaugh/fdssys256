package com.example.mealprep.preference.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.domain.service.PreferenceArchiveQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only REST seam for the preference archive. The current user's id comes from {@link
 * CurrentUserResolver}; the controller never accepts {@code userId} from a query or path parameter,
 * so user A cannot read user B's archive.
 *
 * <p>Only two read endpoints. There is intentionally no POST/PUT — the archive is written
 * exclusively in-process via {@code PreferenceArchiveUpdateService.archiveItem} from the future
 * {@code TasteProfileDeltaApplier} path.
 */
@RestController
@RequestMapping("/api/v1/preferences/archive")
@Tag(name = "Preferences")
public class PreferenceArchiveController {

  private final PreferenceArchiveQueryService queryService;
  private final CurrentUserResolver currentUserResolver;

  public PreferenceArchiveController(
      PreferenceArchiveQueryService queryService, CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated archive of pruned taste-profile items; newest-archived first.")
  public Page<PreferenceArchiveEntryDto> getArchive(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) String fieldPathPrefix) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    if (fieldPathPrefix == null || fieldPathPrefix.isBlank()) {
      return queryService.getArchive(userId, pageable);
    }
    return queryService.getArchiveForField(userId, fieldPathPrefix, pageable);
  }

  @GetMapping(path = "/active-count", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Count of active (not-yet-re-promoted) archive entries for the user.")
  public ActiveCountResponse activeCount() {
    UUID userId = requireCurrentUserId();
    return new ActiveCountResponse(queryService.countActiveEntries(userId));
  }

  /** Inline response body for the {@code /active-count} endpoint. */
  public record ActiveCountResponse(long count) {}

  // ---------------- helpers ----------------

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
