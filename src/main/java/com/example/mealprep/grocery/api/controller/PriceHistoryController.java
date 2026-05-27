package com.example.mealprep.grocery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.api.dto.PriceObservationDto;
import com.example.mealprep.grocery.api.dto.RecordManualPriceRequest;
import com.example.mealprep.grocery.api.dto.RefreshPricesRequest;
import com.example.mealprep.grocery.api.dto.RefreshPricesResultDto;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tier 4 — price history REST surface (01c). Per LLD lines 729-738. {@link CurrentUserResolver}
 * resolves {@code userId} server-side; in single-user mode the {@code userId} doubles as the
 * household aggregation scope (matching the {@code household_id nullable} entity convention).
 *
 * <p>Six endpoints: aggregate by key+store (200/404), cross-store aggregates (200), observations
 * paged (200), observations-by-key paged (200), manual-record (201), refresh (200/503). The 503 on
 * refresh is the {@code AiUnavailableException} path mapped by {@code GroceryExceptionHandler}; in
 * 01c (no provider yet) the refresh always returns 200 with {@code observationsWritten = 0}.
 */
@RestController
@RequestMapping("/api/v1/grocery/price-history")
@Tag(name = "Grocery — Price History")
public class PriceHistoryController {

  private final PriceHistoryService priceHistoryService;
  private final CurrentUserResolver currentUserResolver;

  public PriceHistoryController(
      PriceHistoryService priceHistoryService, CurrentUserResolver currentUserResolver) {
    this.priceHistoryService = priceHistoryService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(path = "/aggregates", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Aggregate (estimate + confidence + range) for a key, optionally per store.")
  public PriceAggregateDto aggregate(
      @RequestParam @NotBlank String ingredientKey, @RequestParam(required = false) String store) {
    UUID householdId = requireCurrentUserId();
    return priceHistoryService
        .getAggregate(householdId, ingredientKey, store)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No price estimate for '" + ingredientKey + "' (unknown ingredient)."));
  }

  @GetMapping(path = "/aggregates/cross-store", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Per-store aggregates for a key (one entry per store).")
  @BoundedCollection("Bounded by the number of stores a household has price history for (small).")
  public List<PriceAggregateDto> crossStore(@RequestParam @NotBlank String ingredientKey) {
    UUID householdId = requireCurrentUserId();
    return priceHistoryService.getCrossStoreAggregatesByKey(householdId, ingredientKey);
  }

  @GetMapping(path = "/observations", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated raw observations for the caller (audit / debug), newest-first.")
  public Page<PriceObservationDto> observations(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return priceHistoryService.getObservations(userId, pageable);
  }

  @GetMapping(path = "/observations/by-key", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Paginated observations for one mapping key (household scope), newest-first.")
  public Page<PriceObservationDto> observationsByKey(
      @RequestParam @NotBlank String ingredientKey,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID householdId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return priceHistoryService.getObservationsByMappingKey(householdId, ingredientKey, pageable);
  }

  @PostMapping(
      path = "/observations/manual",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Record a one-off manual price observation (source = MANUAL).")
  public ResponseEntity<PriceObservationDto> recordManual(
      @Valid @RequestBody RecordManualPriceRequest request) {
    UUID userId = requireCurrentUserId();
    PriceObservationDto dto = priceHistoryService.recordManualPrice(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(dto);
  }

  @PostMapping(
      path = "/refresh",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "On-demand price refresh; 503 if AI features are paused (provider quote).")
  public RefreshPricesResultDto refresh(@Valid @RequestBody RefreshPricesRequest request) {
    UUID userId = requireCurrentUserId();
    return priceHistoryService.refreshOnDemand(userId, request);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
