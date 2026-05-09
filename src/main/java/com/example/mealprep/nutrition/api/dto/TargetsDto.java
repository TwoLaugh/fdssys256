package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.Goal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read shape of a user's nutrition targets. {@code version} is the JPA {@code @Version} value used
 * by the next {@code PUT} as {@code expectedVersion}.
 */
public record TargetsDto(
    UUID id,
    UUID userId,
    Goal goal,
    CalorieTargetDto calories,
    MacroTargetDto protein,
    MacroTargetDto carbs,
    MacroTargetDto fat,
    MacroTargetDto fibre,
    MacroTargetDto satFat,
    String notes,
    List<String> userOverriddenDirections,
    List<PerMealDistributionDto> perMealDistribution,
    List<MicroTargetDto> microTargets,
    EatingWindowDto eatingWindow,
    List<ActivityAdjustmentDto> activityAdjustments,
    Instant createdAt,
    long version) {}
