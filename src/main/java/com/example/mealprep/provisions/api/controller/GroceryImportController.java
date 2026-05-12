package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the grocery-import flow (01h). One endpoint: apply a grocery order. The caller's
 * {@code userId} is resolved server-side; anonymous callers get 401. Idempotent on {@code (userId,
 * supplier, orderRef)} — replay yields 409 {@code duplicate-grocery-import}.
 *
 * <p>Operator-tooling parity with 01g's three new endpoints — the alternative is in-process only,
 * but the REST surface gives integration tests + operators a uniform entry point.
 */
@RestController
@RequestMapping("/api/v1/provisions")
@Tag(name = "Provisions")
public class GroceryImportController {

  private final ProvisionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public GroceryImportController(
      ProvisionUpdateService updateService, CurrentUserResolver currentUserResolver) {
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      path = "/grocery-import",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Apply a grocery-order import; upserts supplier products and creates/merges inventory"
              + " rows by expiry.")
  public GroceryImportResultDto applyGroceryOrder(
      @Valid @RequestBody GroceryOrderImportCommand command) {
    UUID userId = requireCurrentUserId();
    return updateService.applyGroceryOrder(userId, command);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
