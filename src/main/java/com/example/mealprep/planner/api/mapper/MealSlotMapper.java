package com.example.mealprep.planner.api.mapper;

import com.example.mealprep.planner.api.dto.MealSlotDto;
import com.example.mealprep.planner.domain.entity.MealSlot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Maps {@link MealSlot} → {@link com.example.mealprep.planner.api.dto.MealSlotDto}. Lists of slots
 * are sorted by {@code slotIndex} ascending per LLD §Mappers — applied here in {@link
 * #toDtos(List)} so the wire payload is always ordered regardless of fetch order.
 */
@Mapper(componentModel = "spring")
public abstract class MealSlotMapper {

  @Autowired protected ScheduledRecipeMapper scheduledRecipeMapper;

  public MealSlotDto toDto(MealSlot entity) {
    if (entity == null) {
      return null;
    }
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
        scheduledRecipeMapper.toDto(entity.getScheduledRecipe()));
  }

  public List<MealSlotDto> toDtos(List<MealSlot> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<MealSlot> sorted = new ArrayList<>(entities);
    sorted.sort(Comparator.comparingInt(MealSlot::getSlotIndex));
    List<MealSlotDto> out = new ArrayList<>(sorted.size());
    for (MealSlot slot : sorted) {
      out.add(toDto(slot));
    }
    return out;
  }
}
