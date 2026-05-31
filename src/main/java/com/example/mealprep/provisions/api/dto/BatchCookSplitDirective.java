package com.example.mealprep.provisions.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Min;

/**
 * Optional fridge/freezer split directive carried on a {@link CookEventCommand} when {@code
 * isBatchCook == true}. Per LLD §CookEventCommand line 457 and §Flow 1 step 4: a cooked batch is
 * split into a fridge portion (eaten in the next few days) and a freezer portion (longer-term,
 * carries the freezer extension). The {@code BatchCookSplitter} turns this directive into one or
 * two prepared-portion inventory rows ({@code source = BATCH_COOK}, {@code source_recipe_id =
 * recipeId}).
 *
 * <p>{@code fridgeMaxDays} / {@code freezerMaxWeeks} are optional caller overrides; when null the
 * splitter applies its configured defaults (LLD line 611 — fridge {@code today + maxFridgeDays},
 * freezer extension via {@code frozen_at + max_freeze_weeks}). At least one of {@code
 * fridgePortions} / {@code freezerPortions} must be positive — a directive that splits zero
 * portions is rejected by {@code BatchCookSplitter}.
 */
public record BatchCookSplitDirective(
    @Min(0) int fridgePortions,
    @Min(0) int freezerPortions,
    @Nullable @Min(1) Integer fridgeMaxDays,
    @Nullable @Min(1) Integer freezerMaxWeeks) {}
