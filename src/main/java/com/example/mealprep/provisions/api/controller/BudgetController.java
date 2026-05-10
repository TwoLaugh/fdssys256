package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.UpdateBudgetRequest;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.exception.BudgetNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the provisions module's budget aggregate. Cookie-auth required on both endpoints;
 * {@code userId} is resolved server-side via {@link CurrentUserResolver} — the controller never
 * accepts a {@code userId} from path, query or body. {@code GET} returns 404 when no row exists yet
 * — a follow-up {@code PUT} bootstraps it (the LLD's "200 / 404" semantics).
 */
@RestController
@RequestMapping("/api/v1/provisions/budget")
@Tag(name = "Provisions")
@Validated
public class BudgetController {

  private final ProvisionQueryService queryService;
  private final ProvisionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public BudgetController(
      ProvisionQueryService queryService,
      ProvisionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Return the calling user's budget configuration, or 404 if not yet initialised.")
  public BudgetDto get() {
    UUID userId = requireCurrentUserId();
    return queryService.getBudget(userId).orElseThrow(() -> new BudgetNotFoundException(userId));
  }

  @PutMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Insert or update the calling user's budget configuration.")
  public BudgetDto upsert(@Valid @RequestBody UpdateBudgetRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.upsertBudget(userId, request);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
