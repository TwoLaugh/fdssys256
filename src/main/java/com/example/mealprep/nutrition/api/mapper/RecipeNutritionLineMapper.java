package com.example.mealprep.nutrition.api.mapper;

import com.example.mealprep.core.ingredient.IngredientUnitConverter;
import com.example.mealprep.nutrition.api.dto.RecipeIngredientLineDto;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps recipe-side {@link IngredientDto} lines onto the calc input {@link RecipeIngredientLineDto},
 * resolving each line's {@code gramsEstimate} from its {@code quantity} + {@code unit} via {@link
 * IngredientUnitConverter}. Shared by the two recalc entry points (the {@code RecipeUpdatedEvent}
 * listener and the manual-recalc endpoint) so both feed the calc real gram weights.
 *
 * <p>Count units (egg, clove, …) and unrecognised units have no gram conversion — those lines carry
 * {@code gramsEstimate=null}, contribute zero nutrition, and the calc degrades the status so the
 * result is never reported as fully {@code calculated}.
 */
public final class RecipeNutritionLineMapper {

  private RecipeNutritionLineMapper() {}

  public static List<RecipeIngredientLineDto> toCalcLines(List<IngredientDto> ingredients) {
    if (ingredients == null || ingredients.isEmpty()) {
      return List.of();
    }
    List<RecipeIngredientLineDto> out = new ArrayList<>(ingredients.size());
    for (IngredientDto in : ingredients) {
      // Density lookup (volume→grams) keys off the canonical mapping key; fall back to the
      // display name when a line was saved before mapping ran.
      String densityKey =
          in.ingredientMappingKey() != null && !in.ingredientMappingKey().isBlank()
              ? in.ingredientMappingKey()
              : in.displayName();
      BigDecimal grams =
          IngredientUnitConverter.toGrams(in.quantity(), in.unit(), densityKey).orElse(null);
      out.add(
          new RecipeIngredientLineDto(
              in.displayName(), in.ingredientMappingKey(), in.quantity(), in.unit(), grams, null));
    }
    return out;
  }
}
