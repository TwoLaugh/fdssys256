package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import com.example.mealprep.nutrition.api.dto.FoodMoodEntryDto;
import com.example.mealprep.nutrition.api.dto.UpsertFoodMoodEntryRequest;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the food/mood journal aggregate. {@link CurrentUserResolver} resolves {@code
 * userId} server-side; the controller never accepts a {@code userId} from path / query.
 *
 * <p>Endpoints follow the LLD's date-in-path idiom (mirrors {@code IntakeController}). Date-range
 * queries are deferred (LLD does not specify {@code from}/{@code to}); the per-day fetch + the
 * paginated recent-entries listing cover the v1 UI need.
 */
@RestController
@RequestMapping("/api/v1/nutrition/journal")
@Tag(name = "Nutrition")
public class JournalController {

  private final NutritionQueryService queryService;
  private final NutritionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public JournalController(
      NutritionQueryService queryService,
      NutritionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(path = "/{date}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List the calling user's food/mood journal entries for a date.")
  @BoundedCollection("bounded-by-date; journal entries per day are typically < 20")
  public List<FoodMoodEntryDto> getForDay(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    UUID userId = requireCurrentUserId();
    return queryService.getJournalEntriesForDay(userId, date);
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List recent journal entries paginated newest-first.")
  public Page<FoodMoodEntryDto> getRecent(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getRecentJournalEntries(userId, pageable);
  }

  @PostMapping(
      path = "/{date}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create a food/mood journal entry for the calling user.")
  public ResponseEntity<FoodMoodEntryDto> create(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @Valid @RequestBody UpsertFoodMoodEntryRequest request) {
    UUID userId = requireCurrentUserId();
    if (!date.equals(request.onDate())) {
      throw new IllegalArgumentException(
          "Path date " + date + " does not match request body onDate " + request.onDate());
    }
    FoodMoodEntryDto created = updateService.upsertJournalEntry(userId, request);
    URI location = URI.create("/api/v1/nutrition/journal/" + date + "/entries/" + created.id());
    return ResponseEntity.created(location).body(created);
  }

  @PutMapping(
      path = "/{date}/entries/{entryId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Update an existing food/mood journal entry.")
  public FoodMoodEntryDto update(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @PathVariable UUID entryId,
      @Valid @RequestBody UpsertFoodMoodEntryRequest request) {
    UUID userId = requireCurrentUserId();
    if (!date.equals(request.onDate())) {
      throw new IllegalArgumentException(
          "Path date " + date + " does not match request body onDate " + request.onDate());
    }
    return updateService.updateJournalEntry(userId, entryId, request);
  }

  @DeleteMapping(path = "/{date}/entries/{entryId}")
  @Operation(summary = "Hard-delete a food/mood journal entry.")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @PathVariable UUID entryId) {
    UUID userId = requireCurrentUserId();
    // {date} is part of the URL but not used to scope the delete — ownership + entryId match are
    // sufficient. The service-impl does not need to consult `date`; cross-day delete attempts via
    // a wrong-date URL still resolve correctly.
    updateService.deleteJournalEntry(userId, entryId);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
