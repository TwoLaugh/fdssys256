package com.example.mealprep.provisions.api.mapper;

import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.domain.entity.Budget;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Budget entity ↔ DTO mapping. v1 mapper — {@code spendTracking} is always {@code null}.
 * provisions-01f/01h adds a two-arg overload that takes the derived tracking DTO.
 */
@Mapper(componentModel = "spring")
public interface BudgetMapper {

  default BudgetDto toDto(Budget entity) {
    if (entity == null) {
      return null;
    }
    return new BudgetDto(
        entity.getId(),
        entity.getUserId(),
        entity.getWeeklyTarget(),
        entity.getCurrency(),
        entity.getToleranceOver(),
        entity.getPriceSensitivity(),
        entity.isEnabled(),
        null,
        entity.getVersion());
  }

  default List<BudgetDto> toDtos(List<Budget> entities) {
    if (entities == null) {
      return List.of();
    }
    return entities.stream().map(this::toDto).toList();
  }
}
