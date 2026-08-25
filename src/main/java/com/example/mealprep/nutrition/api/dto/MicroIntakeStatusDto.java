package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;

/**
 * One micronutrient's measurement status in an intake aggregate. The intake-side mirror of the
 * planner's {@code NutritionTargetCoverageDocument}, so the frontend's shared row grammar can
 * consume both lenses.
 *
 * <p>{@code MEASURED} means at least one decided slot or snack wrote the key; a measured zero stays
 * MEASURED with {@code actualSoFar} 0. {@code NO_DATA} means no decided source carried the key, so
 * intake is unknown, never zero: {@code actualSoFar} is null and the row exists only for tracked
 * micros (the user's micro targets carrying a floor or cap). {@code unit} is a display hint derived
 * from the key suffix, same derivation as the planner coverage rows.
 */
public record MicroIntakeStatusDto(String key, String unit, BigDecimal actualSoFar, String status) {

  public static final String STATUS_MEASURED = "MEASURED";
  public static final String STATUS_NO_DATA = "NO_DATA";
}
