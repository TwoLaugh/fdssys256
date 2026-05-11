package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import com.example.mealprep.provisions.api.dto.WasteEntryDto;
import com.example.mealprep.provisions.api.dto.WasteSummaryDto;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the append-only waste log. All endpoints require authentication; the resource is
 * per-user. The list / summary endpoints default {@code from} to {@code today - 90 days} and {@code
 * to} to {@code today} when omitted; both reject {@code from > to} via
 * {@code @ValidWasteDateRange}.
 *
 * <p>Idempotency: {@code POST /waste} is intentionally not deduped. Re-posting the same body yields
 * two waste rows — waste is genuinely repeatable ("I threw out two bunches of celery separately").
 * See LLD line 660; ticket 01e Behavioural-Spec §6.4.
 */
@RestController
@RequestMapping("/api/v1/provisions/waste")
@Tag(name = "Provisions")
@Validated
public class WasteController {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int DEFAULT_LOOKBACK_DAYS = 90;

  private final ProvisionQueryService queryService;
  private final ProvisionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;
  private final Clock clock;

  public WasteController(
      ProvisionQueryService queryService,
      ProvisionUpdateService updateService,
      CurrentUserResolver currentUserResolver,
      Clock clock) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
    this.clock = clock;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Log a waste entry. When linked to an inventory item with tracking active, deducts the"
              + " quantity, writes an audit row, and publishes an inventory event.")
  public ResponseEntity<WasteEntryDto> logWaste(@Valid @RequestBody LogWasteRequest request) {
    UUID userId = requireCurrentUserId();
    WasteEntryDto saved = updateService.logWaste(userId, request);
    URI location = URI.create("/api/v1/provisions/waste/" + saved.id());
    return ResponseEntity.status(HttpStatus.CREATED).location(location).body(saved);
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated waste history; defaults to last 90 days.")
  public Page<WasteEntryDto> listWaste(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size)
      throws MethodArgumentNotValidException {
    UUID userId = requireCurrentUserId();
    LocalDate today = LocalDate.now(clock);
    LocalDate effectiveTo = to == null ? today : to;
    LocalDate effectiveFrom = from == null ? today.minusDays(DEFAULT_LOOKBACK_DAYS) : from;
    validateDateRange(effectiveFrom, effectiveTo);
    int effectiveSize = size <= 0 ? DEFAULT_PAGE_SIZE : size;
    Pageable pageable = PageRequest.of(page, effectiveSize);
    return queryService.getWasteEntries(userId, effectiveFrom, effectiveTo, pageable);
  }

  @GetMapping(path = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Aggregate waste over a date window: cost, entries, counts by reason, top items.")
  public WasteSummaryDto summary(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    UUID userId = requireCurrentUserId();
    if (from == null || to == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "from and to are required on the summary endpoint.");
    }
    validateDateRange(from, to);
    return queryService.getWasteSummary(userId, from, to);
  }

  private static void validateDateRange(LocalDate from, LocalDate to) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be on or before to.");
    }
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
