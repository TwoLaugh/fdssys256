package com.example.mealprep.adaptation.api.mapper;

import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity ↔ DTO mapping for {@link AdaptationJob}.
 *
 * <p>{@code inputs} is a {@link com.fasterxml.jackson.databind.JsonNode} on both sides — the mapper
 * passes it through unchanged (no typed record-tree conversion yet; deferred per LLD line 408 to
 * 01f's {@code FingerprintRefresher}).
 *
 * <p>Per LLD §Mappers line 408; verbatim from {@code lld/adaptation-pipeline.md}.
 */
@Mapper(componentModel = "spring")
public interface AdaptationJobMapper {

  default AdaptationJobDto toDto(AdaptationJob entity) {
    if (entity == null) {
      return null;
    }
    return new AdaptationJobDto(
        entity.getId(),
        entity.getRecipeId(),
        entity.getUserId(),
        entity.getCatalogue(),
        entity.getSource(),
        entity.getPriority(),
        entity.getApprovalPolicy(),
        entity.getStatus(),
        entity.getFailureReason(),
        entity.getFailureExcerpt(),
        entity.getInputs(),
        entity.getPromptTemplateVersion(),
        entity.getTraceId(),
        entity.getParentDecisionId(),
        entity.getEnqueuedAt(),
        entity.getStartedAt(),
        entity.getCompletedAt(),
        entity.getDurationMs(),
        entity.getOptimisticVersion());
  }

  default List<AdaptationJobDto> toDtos(List<AdaptationJob> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<AdaptationJobDto> out = new ArrayList<>(entities.size());
    for (AdaptationJob e : entities) {
      out.add(toDto(e));
    }
    return out;
  }
}
