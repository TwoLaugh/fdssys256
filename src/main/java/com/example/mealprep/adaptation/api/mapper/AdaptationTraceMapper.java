package com.example.mealprep.adaptation.api.mapper;

import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
import org.mapstruct.Mapper;

/**
 * Entity ↔ DTO mapping for {@link AdaptationTrace}. Append-only entity — no inverse direction.
 *
 * <p>Per LLD §Mappers line 408; verbatim from {@code lld/adaptation-pipeline.md}.
 */
@Mapper(componentModel = "spring")
public interface AdaptationTraceMapper {

  default AdaptationTraceDto toDto(AdaptationTrace entity) {
    if (entity == null) {
      return null;
    }
    return new AdaptationTraceDto(
        entity.getId(),
        entity.getJobId(),
        entity.getRecipeId(),
        entity.getTraceId(),
        entity.getSource(),
        entity.getPromptTemplateName(),
        entity.getPromptTemplateVersion(),
        entity.getAiCallId(),
        entity.getInputsSnapshot(),
        entity.getRawAiResponse(),
        entity.getCandidates(),
        entity.getChosenCandidateIndex(),
        entity.getClassificationDecision(),
        entity.getFinalDiff(),
        entity.getConfidence(),
        entity.getCharacterPreservationScore(),
        entity.getValidationResult(),
        entity.getOutcomeKind(),
        entity.getOutcomeTargetId(),
        entity.getDurationMs(),
        entity.getCreatedAt());
  }
}
