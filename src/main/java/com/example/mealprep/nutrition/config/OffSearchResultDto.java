package com.example.mealprep.nutrition.config;

import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;

/**
 * Minimal projection of the Open Food Facts {@code /cgi/search.pl?...&json=1} response. OFF returns
 * product nutrition under the {@code nutriments} object; everything not consumed by the pipeline is
 * ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OffSearchResultDto(List<Product> products) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Product(String code, String productName, Nutriments nutriments) {

    /**
     * OFF's search response has no relevance score; default to 0.6 (below the 0.7 review floor).
     */
    public double score() {
      return 0.6;
    }

    public IngredientNutritionDocument toDocument() {
      if (nutriments == null) {
        return new IngredientNutritionDocument(
            null, null, null, null, null, null, null, new HashMap<>(), new HashMap<>());
      }
      Integer calories = nutriments.energyKcal100g() == null ? null : nutriments.energyKcal100g();
      BigDecimal proteinG =
          nutriments.proteins100g() == null ? null : BigDecimal.valueOf(nutriments.proteins100g());
      BigDecimal carbsG =
          nutriments.carbohydrates100g() == null
              ? null
              : BigDecimal.valueOf(nutriments.carbohydrates100g());
      BigDecimal fatG =
          nutriments.fat100g() == null ? null : BigDecimal.valueOf(nutriments.fat100g());
      BigDecimal fibreG =
          nutriments.fiber100g() == null ? null : BigDecimal.valueOf(nutriments.fiber100g());
      BigDecimal satFatG =
          nutriments.saturatedFat100g() == null
              ? null
              : BigDecimal.valueOf(nutriments.saturatedFat100g());
      BigDecimal sugarG =
          nutriments.sugars100g() == null ? null : BigDecimal.valueOf(nutriments.sugars100g());
      return new IngredientNutritionDocument(
          calories,
          proteinG,
          carbsG,
          fatG,
          fibreG,
          satFatG,
          sugarG,
          new HashMap<>(),
          new HashMap<>());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Nutriments(
      @JsonProperty("energy-kcal_100g") Integer energyKcal100g,
      @JsonProperty("proteins_100g") Double proteins100g,
      @JsonProperty("carbohydrates_100g") Double carbohydrates100g,
      @JsonProperty("fat_100g") Double fat100g,
      @JsonProperty("fiber_100g") Double fiber100g,
      @JsonProperty("saturated-fat_100g") Double saturatedFat100g,
      @JsonProperty("sugars_100g") Double sugars100g) {}
}
