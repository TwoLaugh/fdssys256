package com.example.mealprep.planner.domain.service.internal.additions;

import java.util.List;

/**
 * Structured output of the {@code PLANNER_ADDITION_PAIRING} AI task (Phase 2, inc 3). For each
 * addition the deterministic planner picked, the model returns the meal slot it pairs with best +
 * a short natural note. Applied across the week's days; falls back to deterministic placement when
 * the AI is unavailable.
 */
public record AdditionPairingResult(List<AdditionPlacement> placements) {

  /**
   * @param additionName must echo one of the supplied addition names (the join key)
   * @param slotKind one of the supplied slot kinds (BREAKFAST/LUNCH/DINNER/SNACK)
   * @param note the pairing note shown on the slot ("½ avocado on the taco salad")
   */
  public record AdditionPlacement(String additionName, String slotKind, String note) {}
}
