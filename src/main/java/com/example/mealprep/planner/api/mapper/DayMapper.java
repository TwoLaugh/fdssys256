package com.example.mealprep.planner.api.mapper;

import com.example.mealprep.planner.api.dto.DayDto;
import com.example.mealprep.planner.domain.entity.Day;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Maps {@link Day} → {@link DayDto}. Lists of days are sorted by {@code onDate} ascending per LLD
 * §Mappers in {@link #toDtos(List)}.
 */
@Mapper(componentModel = "spring")
public abstract class DayMapper {

  @Autowired protected MealSlotMapper mealSlotMapper;

  public DayDto toDto(Day entity) {
    if (entity == null) {
      return null;
    }
    return new DayDto(
        entity.getId(),
        entity.getOnDate(),
        entity.getNotes(),
        mealSlotMapper.toDtos(entity.getSlots()));
  }

  public List<DayDto> toDtos(List<Day> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<Day> sorted = new ArrayList<>(entities);
    sorted.sort(Comparator.comparing(Day::getOnDate));
    List<DayDto> out = new ArrayList<>(sorted.size());
    for (Day day : sorted) {
      out.add(toDto(day));
    }
    return out;
  }
}
