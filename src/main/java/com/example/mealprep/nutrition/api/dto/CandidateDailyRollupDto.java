package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Per-day rollup of a candidate plan's macros + micros, as supplied by the planner before invoking
 * {@code NutritionFloorGateService#evaluate}. Verbatim per LLD §FloorGate.
 *
 * <p>{@code micros} keys (e.g. {@code "iron_mg"}, {@code "saturatedFatG"}) are normalised by the
 * planner — the gate compares against the matching {@code MicroTarget.nutrientKey} verbatim.
 */
public record CandidateDailyRollupDto(
    @NotNull LocalDate date,
    @NotNull ActivityLevel activityLevel,
    @Min(0) int calories,
    @NotNull @PositiveOrZero BigDecimal proteinG,
    @NotNull @PositiveOrZero BigDecimal carbsG,
    @NotNull @PositiveOrZero BigDecimal fatG,
    @NotNull @PositiveOrZero BigDecimal fibreG,
    @NotNull Map<String, BigDecimal> micros) {}
