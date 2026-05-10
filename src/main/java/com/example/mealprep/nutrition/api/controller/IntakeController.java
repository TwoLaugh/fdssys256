package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.nutrition.api.dto.IntakeAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.IntakeEntryDto;
import com.example.mealprep.nutrition.api.dto.LogSnackRequest;
import com.example.mealprep.nutrition.api.dto.OverrideIntakeRequest;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.exception.IntakeDayNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for intake tracking — day reads, slot writes (confirm / override / edit / skip), snack
 * writes, and the audit log. {@link CurrentUserResolver} resolves {@code userId} server-side; the
 * controller never accepts a {@code userId} from path or query.
 *
 * <p>{@code deductFromPantry} on {@link LogSnackRequest} is accepted but a no-op in 01b — the
 * cross-module pantry-deduct lands in nutrition-01l.
 */
@RestController
@RequestMapping("/api/v1/nutrition/intake")
@Tag(name = "Nutrition")
public class IntakeController {

  private final NutritionQueryService queryService;
  private final NutritionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public IntakeController(
      NutritionQueryService queryService,
      NutritionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(path = "/{date}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Return the calling user's intake for one day.")
  public IntakeDayDto getDay(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    UUID userId = requireCurrentUserId();
    return queryService
        .getIntakeForDay(userId, date)
        .orElseThrow(() -> new IntakeDayNotFoundException(userId, date));
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List intake days in [from, to] (max 35 days).")
  public List<IntakeDayDto> getRange(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    UUID userId = requireCurrentUserId();
    return queryService.getIntakeRange(userId, from, to);
  }

  @PostMapping(
      path = "/{date}/slots/{mealSlot}/confirm",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Confirm a planned slot was eaten as planned.")
  public IntakeDayDto confirm(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @PathVariable MealSlot mealSlot) {
    UUID userId = requireCurrentUserId();
    return updateService.confirmFromPlan(userId, date, mealSlot);
  }

  @PostMapping(
      path = "/{date}/slots/{mealSlot}/override",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Override a slot with verbatim free text (AI parse deferred).")
  public IntakeDayDto override(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @PathVariable MealSlot mealSlot,
      @Valid @RequestBody OverrideIntakeRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.overrideIntakeFromFreeText(userId, date, mealSlot, request.freeText());
  }

  @PostMapping(
      path = "/{date}/slots/{mealSlot}/edit",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Manually set a slot's actual values.")
  public IntakeDayDto edit(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @PathVariable MealSlot mealSlot,
      @Valid @RequestBody IntakeEntryDto entry) {
    UUID userId = requireCurrentUserId();
    return updateService.editIntakeManually(userId, date, mealSlot, entry);
  }

  @PostMapping(path = "/{date}/slots/{mealSlot}/skip", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Mark a slot as skipped.")
  public IntakeDayDto skip(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @PathVariable MealSlot mealSlot) {
    UUID userId = requireCurrentUserId();
    return updateService.skipMeal(userId, date, mealSlot);
  }

  @PostMapping(
      path = "/{date}/snacks",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Log a snack on a date (auto-creates the day row).")
  @ResponseStatus(HttpStatus.CREATED)
  public IntakeDayDto logSnack(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @Valid @RequestBody LogSnackRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.logSnack(userId, date, request);
  }

  @DeleteMapping(path = "/{date}/snacks/{snackId}")
  @Operation(summary = "Delete a snack.")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeSnack(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @PathVariable UUID snackId) {
    UUID userId = requireCurrentUserId();
    updateService.removeSnack(userId, date, snackId);
  }

  @GetMapping(path = "/{date}/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated audit log for a day.")
  public Page<IntakeAuditEntryDto> auditLog(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getIntakeAuditLog(userId, date, pageable);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
