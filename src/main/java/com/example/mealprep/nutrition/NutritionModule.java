package com.example.mealprep.nutrition;

import com.example.mealprep.nutrition.domain.service.NutritionCalculationService;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the nutrition module's public service interfaces. Cross-module callers
 * inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>Mirrors {@code AuthModule} / {@code PreferenceModule} / {@code HouseholdModule}; thin and
 * carries no business logic. 01a lands the targets query / update surfaces; later sub-tickets add
 * intake, journal, ingredient mapping, directives, calculation, floor-gate.
 */
@Component
public class NutritionModule {

  private final NutritionQueryService nutritionQueryService;
  private final NutritionUpdateService nutritionUpdateService;
  private final NutritionCalculationService nutritionCalculationService;

  public NutritionModule(
      NutritionQueryService nutritionQueryService,
      NutritionUpdateService nutritionUpdateService,
      NutritionCalculationService nutritionCalculationService) {
    this.nutritionQueryService = nutritionQueryService;
    this.nutritionUpdateService = nutritionUpdateService;
    this.nutritionCalculationService = nutritionCalculationService;
  }

  public NutritionQueryService query() {
    return nutritionQueryService;
  }

  public NutritionUpdateService update() {
    return nutritionUpdateService;
  }

  public NutritionCalculationService calculation() {
    return nutritionCalculationService;
  }
}
