package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The guideline-default target values computed from a person's demographics (see {@link
 * ComputeTargetsRequest}). A preview the UI loads into the editable targets form — every value is a
 * starting default the user can override. {@code bmr} and {@code ageGroup} are surfaced so the UI
 * can explain where the numbers came from. {@code micros} is {@code nutrientKey → RDA/AI floor}
 * from the matched {@code (ageGroup, sex)} DRI band.
 */
public record ComputedTargetDefaultsDto(
    int calories,
    int bmr,
    String ageGroup,
    BigDecimal proteinG,
    BigDecimal carbsG,
    BigDecimal fatG,
    BigDecimal fibreG,
    BigDecimal satFatG,
    Map<String, BigDecimal> micros) {}
