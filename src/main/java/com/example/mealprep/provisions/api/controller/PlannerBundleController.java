package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.provisions.domain.service.ProvisionForPlannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the planner module's provisions-utilisation read. Single endpoint, single-user only
 * — the caller resolves the {@code userId} from the session cookie via {@link CurrentUserResolver};
 * the controller never accepts a {@code userId} from path, query or body.
 *
 * <p>No authorisation gate beyond authentication — users read their own bundle.
 */
@RestController
@RequestMapping("/api/v1/provisions/planner-bundle")
@Tag(name = "Provisions")
public class PlannerBundleController {

  private final ProvisionForPlannerService plannerService;
  private final CurrentUserResolver currentUserResolver;

  public PlannerBundleController(
      ProvisionForPlannerService plannerService, CurrentUserResolver currentUserResolver) {
    this.plannerService = plannerService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Planner-friendly snapshot of the calling user's inventory, equipment, budget, and"
              + " supplier prices.")
  public ProvisionForPlannerBundleDto get() {
    UUID userId = requireCurrentUserId();
    return plannerService.getBundle(userId);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
