package com.example.mealprep.nutrition.api.mapper;

import com.example.mealprep.nutrition.api.dto.ActivityAdjustmentDto;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.EatingWindowDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.MicroTargetDto;
import com.example.mealprep.nutrition.api.dto.NutritionTargetsAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.PerMealDistributionDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.ActivityAdjustment;
import com.example.mealprep.nutrition.domain.entity.EatingWindow;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.entity.PerMealDistributionEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity ↔ DTO mapping for the nutrition-targets aggregate. Sub-collection mapping is inlined
 * rather than delegated, kept consistent with {@code HouseholdMapper} and {@code
 * HardConstraintsMapper}.
 */
@Mapper(componentModel = "spring")
public interface TargetsMapper {

  default TargetsDto toDto(NutritionTargets entity) {
    if (entity == null) {
      return null;
    }
    return new TargetsDto(
        entity.getId(),
        entity.getUserId(),
        entity.getGoal(),
        new CalorieTargetDto(
            entity.getDailyCalorieTarget(),
            entity.getCalorieToleranceUnder(),
            entity.getCalorieToleranceOver(),
            entity.getCalorieEnforcement(),
            entity.getCalorieDirection()),
        new MacroTargetDto(
            entity.getProteinTargetG(),
            entity.getProteinFloorG(),
            entity.getProteinEnforcement(),
            entity.getProteinDirection()),
        new MacroTargetDto(
            entity.getCarbsTargetG(),
            entity.getCarbsFloorG(),
            entity.getCarbsEnforcement(),
            entity.getCarbsDirection()),
        new MacroTargetDto(
            entity.getFatTargetG(),
            entity.getFatFloorG(),
            entity.getFatEnforcement(),
            entity.getFatDirection()),
        new MacroTargetDto(
            entity.getFibreTargetG(),
            entity.getFibreFloorG(),
            entity.getFibreEnforcement(),
            entity.getFibreDirection()),
        new MacroTargetDto(entity.getSatFatTargetG(), null, null, entity.getSatFatDirection()),
        entity.getNotes(),
        copyOrEmpty(entity.getUserOverriddenDirections()),
        mapPerMeal(entity.getPerMealDistribution()),
        mapMicros(entity.getMicroTargets()),
        mapEatingWindow(entity.getEatingWindow()),
        mapActivityAdjustments(entity.getActivityAdjustments()),
        entity.getCreatedAt(),
        entity.getVersion());
  }

  default NutritionTargetsAuditEntryDto toAuditEntryDto(NutritionTargetsAuditLog entity) {
    if (entity == null) {
      return null;
    }
    return new NutritionTargetsAuditEntryDto(
        entity.getId(),
        entity.getTargetsId(),
        entity.getActorUserId(),
        entity.getActorKind(),
        entity.getSourceDirectiveId(),
        entity.getFieldPath(),
        entity.getPreviousValueJson(),
        entity.getNewValueJson(),
        entity.getOccurredAt());
  }

  private static List<String> copyOrEmpty(List<String> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    return new ArrayList<>(source);
  }

  private static List<PerMealDistributionDto> mapPerMeal(List<PerMealDistributionEntry> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    List<PerMealDistributionDto> result = new ArrayList<>(source.size());
    for (PerMealDistributionEntry e : source) {
      result.add(
          new PerMealDistributionDto(e.getMealSlot(), e.getCalorieTarget(), e.getProteinTargetG()));
    }
    return result;
  }

  private static List<MicroTargetDto> mapMicros(List<MicroTarget> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    List<MicroTargetDto> result = new ArrayList<>(source.size());
    for (MicroTarget m : source) {
      result.add(
          new MicroTargetDto(
              m.getNutrientKey(),
              m.getTargetValue(),
              m.getUpperLimit(),
              m.getSourcePreference(),
              m.getNotes()));
    }
    return result;
  }

  private static EatingWindowDto mapEatingWindow(EatingWindow window) {
    if (window == null) {
      return null;
    }
    return new EatingWindowDto(
        window.isEnabled(), window.getWindowStart(), window.getWindowEnd(), window.getNotes());
  }

  private static List<ActivityAdjustmentDto> mapActivityAdjustments(
      List<ActivityAdjustment> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    List<ActivityAdjustmentDto> result = new ArrayList<>(source.size());
    for (ActivityAdjustment a : source) {
      result.add(
          new ActivityAdjustmentDto(
              a.getActivityLevel(), a.getCalorieModifier(), a.getCarbModifierG()));
    }
    return result;
  }
}
