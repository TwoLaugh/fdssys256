package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.provisions.api.dto.CookEventCommand;
import com.example.mealprep.provisions.api.dto.InventoryDeductionResultDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.MealConsumptionCommand;
import com.example.mealprep.provisions.api.dto.StandaloneConsumptionCommand;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the cook-event + consumption flows (01g). Three endpoints: cook event (recipe-wide
 * FIFO-by-expiry deduction), meal consumption (decrement one specific item), standalone consumption
 * (Nutrition Logger preview/deduct). The caller's {@code userId} is resolved server-side.
 */
@RestController
@RequestMapping("/api/v1/provisions")
@Tag(name = "Provisions")
public class CookEventController {

  private final ProvisionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public CookEventController(
      ProvisionUpdateService updateService, CurrentUserResolver currentUserResolver) {
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      path = "/cook-event",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Apply a cook event for the calling user; deducts ingredients FIFO-by-expiry from the"
              + " pantry.")
  public InventoryDeductionResultDto applyCookEvent(@Valid @RequestBody CookEventCommand command) {
    UUID userId = requireCurrentUserId();
    return updateService.applyCookEvent(userId, command);
  }

  @PostMapping(
      path = "/meal-consumption",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Decrement a specific inventory item by a number of portions.")
  public InventoryDeductionResultDto applyMealConsumption(
      @Valid @RequestBody MealConsumptionCommand command) {
    UUID userId = requireCurrentUserId();
    return updateService.applyMealConsumption(userId, command);
  }

  @PostMapping(
      path = "/standalone-consumption",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Standalone food log — finds an active inventory row matching ingredientMappingKey and"
              + " optionally decrements.")
  public ResponseEntity<InventoryItemDto> applyStandaloneConsumption(
      @Valid @RequestBody StandaloneConsumptionCommand command) {
    UUID userId = requireCurrentUserId();
    Optional<InventoryItemDto> result = updateService.applyStandaloneConsumption(userId, command);
    return ResponseEntity.status(HttpStatus.OK)
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.orElse(null));
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
