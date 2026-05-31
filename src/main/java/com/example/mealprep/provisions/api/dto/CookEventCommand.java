package com.example.mealprep.provisions.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/provisions/cook-event}. The {@code userId} is resolved
 * server-side (caller's session) and is NOT carried on this record. See LLD §CookEventCommand line
 * 449-455; 01g extends the LLD shape with the {@code strict} field (LLD divergence — line 535's
 * strict-mode rule needed a request-level toggle).
 *
 * <p>{@code batchSplit} is the optional fridge/freezer split directive consumed when {@code
 * isBatchCook == true} (LLD §Flow 1 step 4 line 611); {@code batchLabel} is an optional display
 * name for the prepared-portion rows the {@code BatchCookSplitter} creates (defaults to a
 * recipe-derived label when blank).
 */
public record CookEventCommand(
    @NotNull UUID recipeId,
    @Nullable UUID planId,
    @NotNull UUID mealSlotId,
    @Min(1) int servingsCooked,
    @Nullable Boolean isBatchCook,
    @Nullable BigDecimal proportionOfRecipe,
    @Nullable Boolean strict,
    @Nullable @Size(max = 64) String dedupeKey,
    @NotNull @NotEmpty @Valid List<RecipeIngredientUsage> ingredientsUsed,
    @Nullable @Valid BatchCookSplitDirective batchSplit,
    @Nullable @Size(max = 128) String batchLabel,
    @Nullable UUID traceId) {}
