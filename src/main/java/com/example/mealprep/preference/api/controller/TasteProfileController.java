package com.example.mealprep.preference.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.preference.api.dto.RollbackTasteProfileRequest;
import com.example.mealprep.preference.api.dto.TasteProfileAuditEntryDto;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.TasteProfileVersionDto;
import com.example.mealprep.preference.api.dto.TriggerTasteProfileRefreshRequest;
import com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.exception.TasteProfileNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the taste-profile aggregate. The current user's id comes from {@link
 * CurrentUserResolver}; controller never accepts {@code userId} from a query or path parameter so
 * user A cannot read or update user B's profile.
 *
 * <p>The rollback endpoint is intentionally absent in 01c — it ships in a follow-up ticket (see the
 * ticket's "Deferred to other tickets" section).
 */
@RestController
@RequestMapping("/api/v1/preferences/taste-profile")
@Tag(name = "Preferences")
public class TasteProfileController {

  /** Inbound header that callers may use to propagate a trace id; auto-generated when missing. */
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  private final TasteProfileQueryService queryService;
  private final TasteProfileUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public TasteProfileController(
      TasteProfileQueryService queryService,
      TasteProfileUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Read the calling user's taste profile.")
  public TasteProfileDto get() {
    UUID userId = requireCurrentUserId();
    return queryService
        .getTasteProfile(userId)
        .orElseThrow(() -> new TasteProfileNotFoundException(userId));
  }

  @PutMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Replace the calling user's taste profile (manual override).")
  public TasteProfileDto update(@Valid @RequestBody UpdateTasteProfileRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.applyManualOverride(userId, request, userId);
  }

  @PostMapping(
      path = "/refresh-now",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Request an asynchronous AI refresh of the taste profile.")
  public ResponseEntity<TasteProfileDto> refreshNow(
      @Valid @RequestBody(required = false) TriggerTasteProfileRefreshRequest request,
      HttpServletRequest httpRequest) {
    UUID userId = requireCurrentUserId();
    UUID traceId = resolveTraceId(httpRequest);
    TriggerTasteProfileRefreshRequest body =
        request == null ? new TriggerTasteProfileRefreshRequest(null, null) : request;
    TasteProfileDto dto = updateService.triggerRefresh(userId, body, userId, traceId);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(dto);
  }

  @PostMapping(
      path = "/rollback",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Roll the calling user's taste profile back to a prior version (restored as a new"
              + " monotonic version) and replay feedback from that version's cursor forward.")
  public TasteProfileDto rollback(@Valid @RequestBody RollbackTasteProfileRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.rollbackTasteProfile(
        userId, request.targetDocumentVersion(), request.expectedVersion(), userId);
  }

  @GetMapping(path = "/versions", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated taste-profile version snapshots; newest-first.")
  public Page<TasteProfileVersionDto> versions(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getVersions(userId, pageable);
  }

  @GetMapping(path = "/versions/{documentVersion}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Read a single taste-profile version snapshot.")
  public TasteProfileVersionDto versionByNumber(@PathVariable @Min(1) int documentVersion) {
    UUID userId = requireCurrentUserId();
    return queryService
        .getVersion(userId, documentVersion)
        .orElseThrow(() -> new TasteProfileNotFoundException(userId));
  }

  @GetMapping(path = "/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated audit log of taste-profile changes; newest-first.")
  public Page<TasteProfileAuditEntryDto> auditLog(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getAuditLog(userId, pageable);
  }

  // ---------------- helpers ----------------

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }

  private static UUID resolveTraceId(HttpServletRequest httpRequest) {
    String header = httpRequest.getHeader(TRACE_ID_HEADER);
    if (header == null || header.isBlank()) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(header.trim());
    } catch (IllegalArgumentException ex) {
      // Malformed header — fall back to a server-generated id. We log nothing here since this is a
      // hot path and
      // the trace id is non-load-bearing for correctness.
      return UUID.randomUUID();
    }
  }
}
