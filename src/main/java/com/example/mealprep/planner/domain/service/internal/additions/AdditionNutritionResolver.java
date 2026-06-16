package com.example.mealprep.planner.domain.service.internal.additions;

import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.AdditionKind;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves an {@link AdditionCandidate} into a concrete {@link Addition} with USDA-derived
 * per-portion nutrition (Phase 2). Prefers the nutrition module's live ingredient-mapping cache
 * ({@link NutritionQueryService#lookupIngredient} — the real USDA/OFF data once the importer has
 * populated it), and falls back to the candidate's catalogue per-100g (USDA-sourced reference
 * values) when the cache has no row for that key. Either way the per-100g figures are scaled by the
 * portion's grams and every micro is tagged {@code "derived"} provenance so the coverage panel's
 * source blend treats additions as USDA-derived, not measured-for-this-recipe.
 */
@Component
class AdditionNutritionResolver {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** USDA reference values scaled to a portion are "derived", not measured for this exact recipe. */
  private static final String SOURCE_DERIVED = "derived";

  private final NutritionQueryService nutritionQueryService;

  AdditionNutritionResolver(NutritionQueryService nutritionQueryService) {
    this.nutritionQueryService = nutritionQueryService;
  }

  /** Resolve one candidate into an addition with its portion-scaled, provenance-tagged nutrition. */
  Addition resolve(AdditionCandidate candidate) {
    NutritionPerServingDto per100g =
        livePer100g(candidate.ingredientKey()).orElseGet(candidate::per100g);
    BigDecimal factor = candidate.grams().divide(HUNDRED, 6, RoundingMode.HALF_UP);
    NutritionPerServingDto scaled = scale(per100g, factor);
    return new Addition(
        AdditionKind.INGREDIENT,
        candidate.displayName(),
        candidate.ingredientKey(),
        null,
        candidate.quantity(),
        candidate.unit(),
        candidate.grams(),
        scaled,
        null);
  }

  List<Addition> resolveAll(List<AdditionCandidate> candidates) {
    return candidates.stream().map(this::resolve).toList();
  }

  /** Live USDA per-100g from the ingredient-mapping cache, merging the micros + vitamins maps. */
  private Optional<NutritionPerServingDto> livePer100g(String ingredientKey) {
    if (nutritionQueryService == null) {
      return Optional.empty();
    }
    return nutritionQueryService
        .lookupIngredient(ingredientKey)
        .map(IngredientNutritionDto::nutritionPer100g)
        .filter(d -> d != null)
        .map(this::fromDocument);
  }

  private NutritionPerServingDto fromDocument(IngredientNutritionDocument d) {
    Map<String, BigDecimal> micros = new LinkedHashMap<>();
    if (d.micros() != null) {
      micros.putAll(d.micros());
    }
    if (d.vitamins() != null) {
      d.vitamins().forEach(micros::putIfAbsent);
    }
    return new NutritionPerServingDto(
        d.calories() == null ? 0 : d.calories(),
        nz(d.proteinG()),
        nz(d.carbsG()),
        nz(d.fatG()),
        nz(d.fibreG()),
        micros);
  }

  private NutritionPerServingDto scale(NutritionPerServingDto per100g, BigDecimal factor) {
    Map<String, BigDecimal> micros = new LinkedHashMap<>();
    Map<String, String> sources = new LinkedHashMap<>();
    if (per100g.micros() != null) {
      for (Map.Entry<String, BigDecimal> e : per100g.micros().entrySet()) {
        if (e.getKey() != null && e.getValue() != null) {
          micros.put(e.getKey(), e.getValue().multiply(factor).setScale(4, RoundingMode.HALF_UP));
          sources.put(e.getKey(), SOURCE_DERIVED);
        }
      }
    }
    return new NutritionPerServingDto(
        (int) Math.round(per100g.calories() * factor.doubleValue()),
        nz(per100g.proteinG()).multiply(factor).setScale(2, RoundingMode.HALF_UP),
        nz(per100g.carbsG()).multiply(factor).setScale(2, RoundingMode.HALF_UP),
        nz(per100g.fatG()).multiply(factor).setScale(2, RoundingMode.HALF_UP),
        nz(per100g.fibreG()).multiply(factor).setScale(2, RoundingMode.HALF_UP),
        micros,
        sources,
        Map.of());
  }

  private static BigDecimal nz(BigDecimal b) {
    return b == null ? BigDecimal.ZERO : b;
  }
}
