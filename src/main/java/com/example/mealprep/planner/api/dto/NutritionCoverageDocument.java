package com.example.mealprep.planner.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The generated plan's projected nutrition vs the primary user's targets — calories + macros and
 * each configured micronutrient — so the UI can show "this plan delivers N of M targets" and which
 * fall short. Projected values are the plan's daily averages for the primary eater (one serving per
 * slot, per-person). A JSON-only carrier inside {@link RollupSummaryDocument}; {@code null} on the
 * rollup when the user has no nutrition targets configured.
 */
public record NutritionCoverageDocument(
    List<NutritionTargetCoverageDocument> macros,
    List<NutritionTargetCoverageDocument> micros,
    int macrosMet,
    int macrosTotal,
    int microsMet,
    int microsTotal,
    // micros whose intake is UNKNOWN (no recipe carried the nutrient). Excluded from microsMet's
    // denominator when judging coverage: "assessed" = microsTotal - microsNoData.
    int microsNoData,
    // Informational fatty-acid breakdown (USDA-derived weekly-avg g/day). Saturated fat is ALSO a
    // scored macro row in {@code macros} (it has a target); mono/poly are display-only here so the
    // user can see the fat SPREAD (how much of the total fat is unsaturated). {@code null} when the
    // plan carries no fat-subtype data.
    FatBreakdown fatBreakdown) {

  /** Weekly-average fatty-acid split for display (grams/day). */
  public record FatBreakdown(
      BigDecimal saturatedG, BigDecimal monounsaturatedG, BigDecimal polyunsaturatedG) {}

  /** Back-compat ctor (no fat breakdown) — keeps pre-fat-subtype construction sites compiling. */
  public NutritionCoverageDocument(
      List<NutritionTargetCoverageDocument> macros,
      List<NutritionTargetCoverageDocument> micros,
      int macrosMet,
      int macrosTotal,
      int microsMet,
      int microsTotal,
      int microsNoData) {
    this(macros, micros, macrosMet, macrosTotal, microsMet, microsTotal, microsNoData, null);
  }
}
