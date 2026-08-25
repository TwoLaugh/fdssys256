package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Aggregate of one user-day's intake: planned vs actual-so-far totals across calories, the five
 * tracked macros (protein/carbs/fat/fibre/satFat), and any micros logged on the day. {@code
 * caloriesRemaining} can be negative when actuals exceed plan; same applies to each macro's {@code
 * remainingG}. {@code microsActualSoFar} is keyed by nutrient key (e.g. {@code "iron_mg"}).
 *
 * <p>{@code satFat} is computed from the {@code "saturated_fat_g"} entries of the per-slot
 * planned/actual micros documents (and snack micros) — slots without saturated-fat data contribute
 * zero. The raw {@code microsActualSoFar["saturated_fat_g"]} entry is retained alongside it for
 * map-convention consumers.
 *
 * <p>{@code micros} carries the status-aware rows: one MEASURED entry per key in {@code
 * microsActualSoFar} plus one NO_DATA entry per tracked-but-unmeasured micro target (see {@link
 * MicroIntakeStatusDto}). The map is retained alongside for existing consumers.
 */
public record DailyAggregateDto(
    int caloriesPlanned,
    int caloriesActualSoFar,
    int caloriesRemaining,
    MacroAggregateDto protein,
    MacroAggregateDto carbs,
    MacroAggregateDto fat,
    MacroAggregateDto fibre,
    MacroAggregateDto satFat,
    Map<String, BigDecimal> microsActualSoFar,
    List<MicroIntakeStatusDto> micros) {}
