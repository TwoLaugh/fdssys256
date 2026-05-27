package com.example.mealprep.grocery.api.mapper;

import com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto;
import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import org.mapstruct.Mapper;

/**
 * {@link GrocerySubstitutionProposal} → {@link GrocerySubstitutionProposalDto} mapping. Per
 * lld/grocery.md §Mappers. The entity's {@code rawPayload} JSONB is intentionally NOT exposed on
 * the DTO.
 */
@Mapper(componentModel = "spring")
public interface GrocerySubstitutionProposalMapper {

  GrocerySubstitutionProposalDto toDto(GrocerySubstitutionProposal entity);
}
