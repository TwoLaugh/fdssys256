package com.example.mealprep.grocery.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import com.example.mealprep.grocery.api.dto.BulkMarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtResultDto;
import com.example.mealprep.grocery.domain.service.ManualFulfilmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tier 2 — manual fulfilment (mark-bought) REST surface (grocery-01d). Per lld/grocery.md lines
 * 703-705. {@link CurrentUserResolver} resolves {@code userId} server-side. The path {@code lineId}
 * / {@code listId} are the server-side authority — the controller rebinds the request body onto the
 * path ids so a mismatched body cannot mark a different line.
 *
 * <p>Three endpoints under {@code /api/v1/grocery/shopping-lists/{listId}}: mark-bought
 * (200/400/404/409), bulk-mark-bought (200/400/404/409), undo-mark-bought (204/404/409).
 */
@RestController
@RequestMapping("/api/v1/grocery/shopping-lists/{listId}")
@Tag(name = "Grocery — Manual Fulfilment")
public class ManualFulfilmentController {

  private final ManualFulfilmentService manualFulfilmentService;
  private final CurrentUserResolver currentUserResolver;

  public ManualFulfilmentController(
      ManualFulfilmentService manualFulfilmentService, CurrentUserResolver currentUserResolver) {
    this.manualFulfilmentService = manualFulfilmentService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      path = "/lines/{lineId}/mark-bought",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Mark a single shopping-list line bought (price/store optional; over-mark allowed with a"
              + " note). 409 if already bought or a concurrent edit collides.")
  public MarkBoughtResultDto markBought(
      @PathVariable UUID listId,
      @PathVariable UUID lineId,
      @Valid @RequestBody MarkBoughtRequest request) {
    UUID userId = requireCurrentUserId();
    // The path lineId is authoritative — rebind the body onto it.
    MarkBoughtRequest bound =
        new MarkBoughtRequest(
            lineId,
            request.boughtQuantity(),
            request.boughtUnit(),
            request.boughtPricePence(),
            request.store(),
            request.boughtAt());
    return manualFulfilmentService.markBought(userId, bound);
  }

  @PostMapping(
      path = "/bulk-mark-bought",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Mark several lines bought in one operation. With totalSpendPence the spend is split"
              + " proportionally by estimate (uniform fallback for no-estimate lines); one"
              + " inventory write + one event for the batch.")
  @BoundedCollection("One result per line in the request (the request line set is user-bounded).")
  public List<MarkBoughtResultDto> bulkMarkBought(
      @PathVariable UUID listId, @Valid @RequestBody BulkMarkBoughtRequest request) {
    UUID userId = requireCurrentUserId();
    // The path listId is authoritative — rebind the body onto it.
    BulkMarkBoughtRequest bound =
        new BulkMarkBoughtRequest(
            listId,
            request.shoppingListLineIds(),
            request.totalSpendPence(),
            request.store(),
            request.boughtAt());
    return manualFulfilmentService.bulkMarkBought(userId, bound);
  }

  @PostMapping(path = "/lines/{lineId}/undo-mark-bought")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary =
          "Undo a Tier-2 mark-bought: line back to UNFILLED + a compensating (never deleted) price"
              + " note. Inventory is NOT auto-reversed (no provisions reverse API) — correct it"
              + " manually. 404 if missing; 409 if the line is not currently bought.")
  public void undoMarkBought(@PathVariable UUID listId, @PathVariable UUID lineId) {
    UUID userId = requireCurrentUserId();
    manualFulfilmentService.undoMarkBought(lineId, userId);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
