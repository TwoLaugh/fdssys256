package com.example.mealprep.grocery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.grocery.api.dto.ExportFormat;
import com.example.mealprep.grocery.api.dto.RecalculateShoppingListRequest;
import com.example.mealprep.grocery.api.dto.ShoppingListDto;
import com.example.mealprep.grocery.api.dto.ShoppingListExportDto;
import com.example.mealprep.grocery.domain.service.ShoppingListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tier 1 — shopping-list REST surface (grocery-01b). Per lld/grocery.md lines 689-697. {@link
 * CurrentUserResolver} resolves {@code userId} server-side.
 *
 * <p>Five endpoints under {@code /api/v1/grocery/shopping-lists}: get-by-id (200/404), current for
 * a plan (200/404), paginated history (200), recalculate (200/400/404), and export (200/404).
 */
@RestController
@RequestMapping("/api/v1/grocery/shopping-lists")
@Tag(name = "Grocery — Shopping List")
public class ShoppingListController {

  private final ShoppingListService shoppingListService;
  private final CurrentUserResolver currentUserResolver;

  public ShoppingListController(
      ShoppingListService shoppingListService, CurrentUserResolver currentUserResolver) {
    this.shoppingListService = shoppingListService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(path = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated shopping-list history for the caller, newest-first.")
  public Page<ShoppingListDto> history(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return shoppingListService.getHistory(userId, pageable);
  }

  @GetMapping(path = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "The current (non-superseded) shopping list for a plan.")
  public ShoppingListDto current(@RequestParam UUID planId) {
    requireCurrentUserId();
    return shoppingListService
        .getCurrentByPlanId(planId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No shopping list for plan '" + planId + "'."));
  }

  @GetMapping(path = "/{shoppingListId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch a shopping list by id (with its lines).")
  public ShoppingListDto getById(@PathVariable UUID shoppingListId) {
    requireCurrentUserId();
    return shoppingListService
        .getById(shoppingListId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Shopping list '" + shoppingListId + "' not found."));
  }

  @PostMapping(
      path = "/recalculate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Recalculate the shopping list from a plan + provisions snapshot.")
  public ShoppingListDto recalculate(@Valid @RequestBody RecalculateShoppingListRequest request) {
    UUID userId = requireCurrentUserId();
    return shoppingListService.recalculate(userId, request);
  }

  @GetMapping(path = "/{shoppingListId}/export", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Render a shopping list as PRINTABLE_HTML | PLAIN_TEXT | MARKDOWN | CSV (PDF is"
              + " print-to-PDF on PRINTABLE_HTML, frontend-side).")
  public ShoppingListExportDto export(
      @PathVariable UUID shoppingListId,
      @RequestParam(defaultValue = "PRINTABLE_HTML") ExportFormat format) {
    requireCurrentUserId();
    return shoppingListService.export(shoppingListId, format);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
