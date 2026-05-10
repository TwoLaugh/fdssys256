package com.example.mealprep.nutrition.api.mapper;

import com.example.mealprep.nutrition.api.dto.ActualIntakeDto;
import com.example.mealprep.nutrition.api.dto.IntakeAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.IntakeSlotDto;
import com.example.mealprep.nutrition.api.dto.IntakeSnackDto;
import com.example.mealprep.nutrition.api.dto.PlannedIntakeDto;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditLog;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.mapstruct.Mapper;

/** Entity ↔ DTO mapping for the intake aggregate (day, slot, snack, audit). */
@Mapper(componentModel = "spring")
public interface IntakeMapper {

  default IntakeDayDto toDto(IntakeDay entity) {
    if (entity == null) {
      return null;
    }
    return new IntakeDayDto(
        entity.getId(),
        entity.getUserId(),
        entity.getOnDate(),
        entity.getPlanId(),
        mapSlots(entity.getSlots()),
        mapSnacks(entity.getSnacks()),
        entity.getVersion());
  }

  default IntakeSlotDto toSlotDto(IntakeSlot entity) {
    if (entity == null) {
      return null;
    }
    PlannedIntakeDto planned =
        new PlannedIntakeDto(
            entity.getPlannedRecipeId(),
            entity.getPlannedCalories(),
            entity.getPlannedProteinG(),
            entity.getPlannedCarbsG(),
            entity.getPlannedFatG(),
            entity.getPlannedFibreG(),
            entity.getPlannedMicros());
    ActualIntakeDto actual =
        new ActualIntakeDto(
            entity.getActualStatus(),
            entity.getActualCalories(),
            entity.getActualProteinG(),
            entity.getActualCarbsG(),
            entity.getActualFatG(),
            entity.getActualFibreG(),
            entity.getActualMicros(),
            entity.getOverrideFreeText(),
            entity.getOverriddenAt(),
            entity.isNeedsAiParse());
    return new IntakeSlotDto(entity.getId(), entity.getMealSlot(), planned, actual);
  }

  default IntakeSnackDto toSnackDto(IntakeSnack entity) {
    if (entity == null) {
      return null;
    }
    return new IntakeSnackDto(
        entity.getId(),
        entity.getIngredientMappingKey(),
        entity.getFreeText(),
        entity.getQuantityG(),
        entity.getCalories(),
        entity.getProteinG(),
        entity.getCarbsG(),
        entity.getFatG(),
        entity.getFibreG(),
        entity.getMicros(),
        entity.getSource(),
        entity.getLoggedAt());
  }

  default IntakeAuditEntryDto toAuditEntryDto(IntakeAuditLog entity) {
    if (entity == null) {
      return null;
    }
    return new IntakeAuditEntryDto(
        entity.getId(),
        entity.getIntakeDayId(),
        entity.getActorUserId(),
        entity.getAction(),
        entity.getMealSlot(),
        entity.getSnackId(),
        entity.getPreviousValueJson(),
        entity.getNewValueJson(),
        entity.getOccurredAt());
  }

  private List<IntakeSlotDto> mapSlots(List<IntakeSlot> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    List<IntakeSlotDto> out = new ArrayList<>(source.size());
    for (IntakeSlot s : source) {
      out.add(toSlotDto(s));
    }
    out.sort(
        Comparator.comparing(
            IntakeSlotDto::mealSlot, Comparator.nullsFirst(Comparator.naturalOrder())));
    return out;
  }

  private List<IntakeSnackDto> mapSnacks(List<IntakeSnack> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    List<IntakeSnackDto> out = new ArrayList<>(source.size());
    for (IntakeSnack s : source) {
      out.add(toSnackDto(s));
    }
    out.sort(
        Comparator.comparing(
            IntakeSnackDto::loggedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    return out;
  }
}
