package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;

/**
 * Saturated-fat aggregate inside {@link DailyAggregateDto}. Unlike the four column-backed macros,
 * satFat is read from the slot/snack micros documents, so a day can carry no measurement at all.
 *
 * <p>{@code MEASURED} means at least one decided slot or snack wrote the saturated-fat key; a
 * written zero stays MEASURED with {@code actualSoFarG} 0. {@code NO_DATA} means none did, so
 * intake is unknown, never zero: {@code actualSoFarG} and {@code remainingG} are null. Same rule as
 * {@link MicroIntakeStatusDto}. {@code plannedG} always sums the planned micros documents.
 */
public record SatFatAggregateDto(
    BigDecimal plannedG, BigDecimal actualSoFarG, BigDecimal remainingG, String status) {

  public static final String STATUS_MEASURED = "MEASURED";
  public static final String STATUS_NO_DATA = "NO_DATA";
}
