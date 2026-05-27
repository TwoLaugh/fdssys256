package com.example.mealprep.grocery.api.mapper;

import com.example.mealprep.grocery.api.dto.ShoppingListDto;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import org.mapstruct.Mapper;

/**
 * {@link ShoppingList} → {@link ShoppingListDto} mapping. Per lld/grocery.md §Mappers — uses {@link
 * ShoppingListLineMapper} for the {@code lines} collection. Tier-ticket bodies (01b) resolve any
 * service-supplied derived fields.
 */
@Mapper(componentModel = "spring", uses = ShoppingListLineMapper.class)
public interface ShoppingListMapper {

  ShoppingListDto toDto(ShoppingList entity);
}
