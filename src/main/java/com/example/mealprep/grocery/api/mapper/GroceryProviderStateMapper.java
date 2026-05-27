package com.example.mealprep.grocery.api.mapper;

import com.example.mealprep.grocery.api.dto.GroceryProviderStateDto;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import org.mapstruct.Mapper;

/**
 * {@link GroceryProviderState} → {@link GroceryProviderStateDto} mapping. Per lld/grocery.md
 * §Mappers. {@code sessionState} (cookies) is intentionally NOT exposed on the DTO — only the
 * non-secret connection metadata round-trips.
 */
@Mapper(componentModel = "spring")
public interface GroceryProviderStateMapper {

  GroceryProviderStateDto toDto(GroceryProviderState entity);
}
