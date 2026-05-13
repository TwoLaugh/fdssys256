package com.example.mealprep.adaptation.api.mapper;

import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeListItemDto;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

/**
 * Entity ↔ DTO mapping for {@link PendingChange}.
 *
 * <p>{@code reasoningPreview} on the list-item shape is a server-side truncation of {@code
 * reasoning} (max {@value #REASONING_PREVIEW_MAX_LEN} chars) via the
 * {@code @Named("truncateReasoning")} qualifier — see {@link #truncateReasoning(String)}. Keeps
 * list responses scannable; the full reasoning is available on the single-row {@link
 * PendingChangeDto}.
 *
 * <p>Per LLD §Mappers line 408; verbatim from {@code lld/adaptation-pipeline.md}.
 */
@Mapper(componentModel = "spring")
public interface PendingChangeMapper {

  /** Max length of the server-side reasoning preview attached to list-item rows. */
  int REASONING_PREVIEW_MAX_LEN = 200;

  default PendingChangeDto toDto(PendingChange entity) {
    if (entity == null) {
      return null;
    }
    return new PendingChangeDto(
        entity.getId(),
        entity.getRecipeId(),
        entity.getUserId(),
        entity.getJobId(),
        entity.getTraceId(),
        entity.getChangeDimension(),
        entity.getProposedClassification(),
        entity.getBaseVersionId(),
        entity.getBaseBranchId(),
        entity.getProposedDiff(),
        entity.getReasoning(),
        entity.getNutritionalNotes(),
        entity.getConfidence(),
        entity.getImpactScore(),
        entity.getPromptTemplateVersion(),
        entity.getStatus(),
        entity.getSupersededBy(),
        entity.getAcceptedVersionId(),
        entity.getUserEdits(),
        entity.getCreatedAt(),
        entity.getExpiresAt(),
        entity.getResolvedAt(),
        entity.getOptimisticVersion());
  }

  default PendingChangeListItemDto toListItem(PendingChange entity) {
    if (entity == null) {
      return null;
    }
    return new PendingChangeListItemDto(
        entity.getId(),
        entity.getRecipeId(),
        entity.getChangeDimension(),
        truncateReasoning(entity.getReasoning()),
        entity.getConfidence(),
        entity.getImpactScore(),
        entity.getCreatedAt(),
        entity.getExpiresAt());
  }

  default List<PendingChangeListItemDto> toListItems(List<PendingChange> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<PendingChangeListItemDto> out = new ArrayList<>(entities.size());
    for (PendingChange e : entities) {
      out.add(toListItem(e));
    }
    return out;
  }

  /**
   * Truncate the reasoning text to at most {@link #REASONING_PREVIEW_MAX_LEN} characters. {@code
   * null} reasoning maps to {@code null}; shorter strings pass through unchanged.
   */
  @Named("truncateReasoning")
  default String truncateReasoning(String reasoning) {
    if (reasoning == null) {
      return null;
    }
    if (reasoning.length() <= REASONING_PREVIEW_MAX_LEN) {
      return reasoning;
    }
    return reasoning.substring(0, REASONING_PREVIEW_MAX_LEN);
  }
}
