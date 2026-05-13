package com.example.mealprep.feedback.api.mapper;

import com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto;
import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import org.mapstruct.Mapper;

/** Entity → DTO mapping for {@link MisclassificationCorrection}. */
@Mapper(componentModel = "spring")
public interface MisclassificationCorrectionMapper {

  default MisclassificationCorrectionDto toDto(MisclassificationCorrection entity) {
    if (entity == null) {
      return null;
    }
    return new MisclassificationCorrectionDto(
        entity.getId(),
        entity.getFeedbackEntry() != null ? entity.getFeedbackEntry().getId() : null,
        entity.getOriginalRoutingId(),
        entity.getCorrectedDestination(),
        entity.getOriginalDestination(),
        entity.getOriginalConfidence(),
        entity.getUserCorrectionNote(),
        entity.getActorUserId(),
        entity.getReplayRoutingId(),
        entity.getReplayStatus(),
        entity.getOccurredAt(),
        entity.getCreatedAt());
  }
}
