package com.example.mealprep.nutrition.config;

import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal projection of the USDA FoodData Central {@code /foods/search} response. Only the fields
 * the pipeline consumes are decoded; everything else is ignored via
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsdaSearchResultDto(List<Food> foods) {

  /** A single hit. {@code score} is USDA's relevance ranking; pipeline caps at 0.85. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Food(
      Integer fdcId, String description, Double score, List<FoodNutrient> foodNutrients) {

    public String fdcIdString() {
      return fdcId == null ? null : fdcId.toString();
    }

    /**
     * Project the nutrient list into the canonical {@link IngredientNutritionDocument}. Recognised
     * USDA nutrient names map to typed scalars; everything else goes into the {@code micros} map
     * keyed by lowercase + underscored nutrient name + unit.
     */
    public IngredientNutritionDocument toDocument() {
      Integer calories = null;
      BigDecimal proteinG = null;
      BigDecimal carbsG = null;
      BigDecimal fatG = null;
      BigDecimal fibreG = null;
      BigDecimal satFatG = null;
      BigDecimal sugarG = null;
      Map<String, BigDecimal> micros = new HashMap<>();
      Map<String, BigDecimal> vitamins = new HashMap<>();
      if (foodNutrients == null) {
        return new IngredientNutritionDocument(
            null, null, null, null, null, null, null, micros, vitamins);
      }
      for (FoodNutrient n : foodNutrients) {
        if (n == null || n.nutrientName() == null || n.value() == null) {
          continue;
        }
        String name = n.nutrientName().toLowerCase().trim();
        BigDecimal value = BigDecimal.valueOf(n.value());
        switch (name) {
          case "energy" -> calories = n.value().intValue();
          case "protein" -> proteinG = value;
          case "carbohydrate, by difference" -> carbsG = value;
          case "total lipid (fat)" -> fatG = value;
          case "fiber, total dietary" -> fibreG = value;
          case "fatty acids, total saturated" -> satFatG = value;
          case "sugars, total including nlea" -> sugarG = value;
          default -> {
            String key = name.replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "");
            if (name.contains("vitamin")) {
              vitamins.put(key, value);
            } else {
              micros.put(key, value);
            }
          }
        }
      }
      return new IngredientNutritionDocument(
          calories, proteinG, carbsG, fatG, fibreG, satFatG, sugarG, micros, vitamins);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FoodNutrient(String nutrientName, String unitName, Double value) {}
}
