package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Pre-allocated slot shell the search fills in. The composer (planner-01j) materialises this from
 * the household's {@code SlotConfiguration} document plus the week-start-date; 01d treats the
 * skeleton list as a frozen input.
 *
 * <p>{@code timeBudgetMin} drives the {@code HardFilterRunner}'s time filter (drop recipes whose
 * {@code totalTimeMins > timeBudgetMin * maxTimeOvershootRatio}). {@code shared == true} routes the
 * recipe's hard-constraint check through {@code checkForHousehold(...)} with the {@code eaters}
 * list; {@code shared == false} runs the per-eater {@code check(userId, ...)} loop.
 */
public record MealSlotSkeleton(
    UUID dayId,
    UUID slotId,
    int slotIndex,
    LocalDate onDate,
    SlotKind kind,
    String label,
    int timeBudgetMin,
    boolean shared,
    List<UUID> eaters) {}
