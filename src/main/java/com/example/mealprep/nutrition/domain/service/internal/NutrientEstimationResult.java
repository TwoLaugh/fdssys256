package com.example.mealprep.nutrition.domain.service.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * Structured output of {@link NutrientEstimationTask}: per-serving estimates for the micronutrients
 * a data source could not supply. Deliberately low-trust — every value the pipeline writes from
 * this result is tagged {@code source="estimated"} with the model's {@code confidence}, so coverage
 * never presents an AI guess as measured or USDA-derived data.
 *
 * <p>{@code @JsonIgnoreProperties} so a model that adds a stray field (e.g. a per-estimate {@code
 * reasoning}) does not fail deserialisation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NutrientEstimationResult(List<Estimate> estimates) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Estimate(String nutrientKey, BigDecimal perServingValue, BigDecimal confidence) {}

  /** Null-safe accessor — a model that omits the array yields an empty list, never an NPE. */
  public List<Estimate> estimatesOrEmpty() {
    return estimates == null ? List.of() : estimates;
  }
}
