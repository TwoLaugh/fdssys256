package com.example.mealprep.planner.api.dto;

import com.example.mealprep.planner.domain.entity.SlotState;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/v1/plans/{planId}/slots/{slotId}/state} (planner-01j).
 * Transitions an individual {@code MealSlot.state}. The state-machine legality check runs in the
 * service layer ({@code PlanStateMachine.assertSlotTransitionAllowed}) and surfaces as 409, not at
 * validation time.
 */
public record SlotStateChangeRequest(@NotNull SlotState newState) {}
