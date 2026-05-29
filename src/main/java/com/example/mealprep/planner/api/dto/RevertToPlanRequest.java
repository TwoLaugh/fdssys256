package com.example.mealprep.planner.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/plans/revert} (planner revert-to-historical, LLD §Flow 4).
 *
 * <p>{@code targetHistoricalPlanId} is the plan the user picked from their history to revert to —
 * its day/slot/scheduled-recipe content is copied forward onto a new active generation. The target
 * fully determines the {@code (household, week)} scope, so no {@code planId} path variable is
 * needed; the {@code userId} is resolved server-side from the auth context, and the service
 * cross-checks that the target belongs to the caller's household (otherwise {@code
 * RevertTargetNotInHistoryException}, 422).
 *
 * <p>This replaces the earlier clone-active {@code POST /{planId}/revert} contract: revert now
 * means "revert TO a chosen historical plan", per the HLD use-case "browse historical plans …
 * revert to plan version N" ([design/meal-planner.md §Plan history and revert]).
 */
public record RevertToPlanRequest(@NotNull UUID targetHistoricalPlanId) {}
