package com.example.mealprep.grocery.api.mapper;

import com.example.mealprep.grocery.api.dto.ShoppingListLineDto;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * {@link ShoppingListLine} → {@link ShoppingListLineDto} mapping. Per lld/grocery.md §Mappers. The
 * entity's {@code staleEstimate} field maps to the DTO's {@code isStaleEstimate} component.
 * Tier-ticket bodies (01b) add any custom transformations.
 */
@Mapper(componentModel = "spring")
public interface ShoppingListLineMapper {

  @Mapping(target = "isStaleEstimate", source = "staleEstimate")
  ShoppingListLineDto toDto(ShoppingListLine entity);
}
