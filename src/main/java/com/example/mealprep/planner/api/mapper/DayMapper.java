package com.example.mealprep.planner.api.mapper;

import com.example.mealprep.planner.api.dto.DayDto;
import com.example.mealprep.planner.domain.entity.Day;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Maps {@link Day} → {@link DayDto}. Lists of days are sorted by {@code onDate} ascending per LLD
 * §Mappers in {@link #toDtos(List, Map)}. The {@code mealTimingMap} is threaded to the slot mapper
 * so each slot's {@code effectiveMealTime} resolves against the household owner's lifestyle-config
 * schedule (loaded once per plan; frontend-gaps: planner-effective-meal-time). The no-map overloads
 * pass an empty map (no lifestyle config available).
 */
@Mapper(componentModel = "spring")
public abstract class DayMapper {

  @Autowired protected MealSlotMapper mealSlotMapper;

  public DayDto toDto(Day entity) {
    return toDto(entity, Map.of());
  }

  public DayDto toDto(Day entity, Map<String, String> mealTimingMap) {
    if (entity == null) {
      return null;
    }
    return new DayDto(
        entity.getId(),
        entity.getOnDate(),
        entity.getNotes(),
        mealSlotMapper.toDtos(entity.getSlots(), mealTimingMap));
  }

  public List<DayDto> toDtos(List<Day> entities) {
    return toDtos(entities, Map.of());
  }

  public List<DayDto> toDtos(List<Day> entities, Map<String, String> mealTimingMap) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<Day> sorted = new ArrayList<>(entities);
    sorted.sort(Comparator.comparing(Day::getOnDate));
    List<DayDto> out = new ArrayList<>(sorted.size());
    for (Day day : sorted) {
      out.add(toDto(day, mealTimingMap));
    }
    return out;
  }
}
