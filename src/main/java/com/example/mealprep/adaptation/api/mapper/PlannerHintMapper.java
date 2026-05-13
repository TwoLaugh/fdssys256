package com.example.mealprep.adaptation.api.mapper;

import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.domain.entity.PlannerHintRecord;
import org.mapstruct.Mapper;

/**
 * Entity ↔ DTO mapping for {@link PlannerHintRecord}.
 *
 * <p>Per LLD §Mappers line 408; verbatim from {@code lld/adaptation-pipeline.md}.
 */
@Mapper(componentModel = "spring")
public interface PlannerHintMapper {

  default PlannerHintDto toDto(PlannerHintRecord entity) {
    if (entity == null) {
      return null;
    }
    return new PlannerHintDto(
        entity.getId(),
        entity.getHintType(),
        entity.getDescription(),
        entity.getPayload(),
        entity.getSeverity());
  }
}
