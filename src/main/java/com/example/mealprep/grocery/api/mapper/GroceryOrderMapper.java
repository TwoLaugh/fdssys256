package com.example.mealprep.grocery.api.mapper;

import com.example.mealprep.grocery.api.dto.GroceryOrderDto;
import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * {@link GroceryOrder} → {@link GroceryOrderDto} mapping. Per lld/grocery.md §Mappers — uses {@link
 * GroceryOrderLineMapper} (and, for the resolved-proposals overload added in a tier ticket, {@link
 * GrocerySubstitutionProposalMapper}).
 *
 * <p>{@code outstandingProposals} is service-supplied (proposals are queried separately, NOT loaded
 * with the order aggregate) — ignored in the entity-only mapping; the Tier-3 service (grocery-01f)
 * supplies it via a custom-mapping overload.
 */
@Mapper(
    componentModel = "spring",
    uses = {GroceryOrderLineMapper.class, GrocerySubstitutionProposalMapper.class})
public interface GroceryOrderMapper {

  @Mapping(target = "outstandingProposals", ignore = true)
  GroceryOrderDto toDto(GroceryOrder entity);
}
