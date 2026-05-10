package com.example.mealprep.nutrition.api.mapper;

import com.example.mealprep.nutrition.api.dto.FoodMoodEntryDto;
import com.example.mealprep.nutrition.domain.entity.FoodMoodJournalEntry;
import java.util.ArrayList;
import java.util.List;
import org.mapstruct.Mapper;

/** Entity ↔ DTO mapping for {@link FoodMoodJournalEntry}. */
@Mapper(componentModel = "spring")
public interface JournalMapper {

  default FoodMoodEntryDto toDto(FoodMoodJournalEntry entity) {
    if (entity == null) {
      return null;
    }
    return new FoodMoodEntryDto(
        entity.getId(),
        entity.getUserId(),
        entity.getOnDate(),
        entity.getMealSlot(),
        entity.getJournalEntry(),
        entity.getLoggedAt(),
        entity.getOptimisticVersion());
  }

  default List<FoodMoodEntryDto> toDtos(List<FoodMoodJournalEntry> entities) {
    if (entities == null) {
      return List.of();
    }
    List<FoodMoodEntryDto> out = new ArrayList<>(entities.size());
    for (FoodMoodJournalEntry e : entities) {
      out.add(toDto(e));
    }
    return out;
  }
}
