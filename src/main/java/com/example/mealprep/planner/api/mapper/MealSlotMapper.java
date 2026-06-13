package com.example.mealprep.planner.api.mapper;

import com.example.mealprep.planner.api.dto.MealSlotDto;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.service.internal.MealTimeResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Maps {@link MealSlot} → {@link com.example.mealprep.planner.api.dto.MealSlotDto}. Lists of slots
 * are sorted by {@code slotIndex} ascending per LLD §Mappers — applied here in {@link #toDtos(List,
 * Map)} so the wire payload is always ordered regardless of fetch order.
 *
 * <p>{@code effectiveMealTime} + {@code mealTimeSource} (frontend-gaps:
 * planner-effective-meal-time) are resolved here through the shared {@link MealTimeResolver} — the
 * single home for the slot override → lifestyle-schedule → kind-default coalesce ({@code
 * getUpcomingSlots} resolves through the same class). The caller passes the household owner's
 * {@code meal_timing} map, loaded <b>once per plan</b> (see {@code
 * PlannerServiceImpl#hydrateAndMap}) so the preference read cost is per-plan, never per-slot. The
 * no-map overloads pass an empty map — the pre-onboarding / no-lifestyle-config case, which
 * resolves to the slot override or the slot-kind default (never a 500).
 */
@Mapper(componentModel = "spring")
public abstract class MealSlotMapper {

  @Autowired protected ScheduledRecipeMapper scheduledRecipeMapper;

  public MealSlotDto toDto(MealSlot entity) {
    return toDto(entity, Map.of());
  }

  public MealSlotDto toDto(MealSlot entity, Map<String, String> mealTimingMap) {
    if (entity == null) {
      return null;
    }
    MealTimeResolver.Resolved resolved = MealTimeResolver.resolve(entity, mealTimingMap);
    return new MealSlotDto(
        entity.getId(),
        entity.getSlotIndex(),
        entity.getKind(),
        entity.getLabel(),
        entity.getTimeBudgetMin(),
        entity.isShared(),
        entity.getEaters() == null ? Collections.emptyList() : List.copyOf(entity.getEaters()),
        entity.getState(),
        entity.getPinnedReason(),
        entity.getMealTime(),
        entity.getPrepStepAtTime(),
        scheduledRecipeMapper.toDto(entity.getScheduledRecipe()),
        resolved.time(),
        resolved.source());
  }

  public List<MealSlotDto> toDtos(List<MealSlot> entities) {
    return toDtos(entities, Map.of());
  }

  public List<MealSlotDto> toDtos(List<MealSlot> entities, Map<String, String> mealTimingMap) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<MealSlot> sorted = new ArrayList<>(entities);
    sorted.sort(Comparator.comparingInt(MealSlot::getSlotIndex));
    List<MealSlotDto> out = new ArrayList<>(sorted.size());
    for (MealSlot slot : sorted) {
      out.add(toDto(slot, mealTimingMap));
    }
    return out;
  }
}
