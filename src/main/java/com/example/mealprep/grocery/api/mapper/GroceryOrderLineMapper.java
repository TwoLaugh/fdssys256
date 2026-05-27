package com.example.mealprep.grocery.api.mapper;

import com.example.mealprep.grocery.api.dto.GroceryOrderLineDto;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import org.mapstruct.Mapper;

/** {@link GroceryOrderLine} → {@link GroceryOrderLineDto} mapping. Per lld/grocery.md §Mappers. */
@Mapper(componentModel = "spring")
public interface GroceryOrderLineMapper {

  GroceryOrderLineDto toDto(GroceryOrderLine entity);
}
