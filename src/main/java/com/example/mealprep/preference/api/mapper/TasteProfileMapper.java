package com.example.mealprep.preference.api.mapper;

import com.example.mealprep.preference.api.dto.TasteProfileAuditEntryDto;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.TasteProfileVersionDto;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteProfileAuditLog;
import com.example.mealprep.preference.domain.entity.TasteProfileVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/** Entity ↔ DTO mapping for the taste profile aggregate, version history, and audit log. */
@Mapper(componentModel = "spring")
public interface TasteProfileMapper {

  default TasteProfileDto toDto(TasteProfile entity) {
    if (entity == null) {
      return null;
    }
    return new TasteProfileDto(
        entity.getId(),
        entity.getUserId(),
        entity.getDocument(),
        entity.getDocumentVersion(),
        entity.getFeedbackCursor(),
        entity.getBasedOnFeedbackCount(),
        entity.getLastDeltaAppliedAt(),
        entity.getLastTokenEstimate(),
        entity.getTasteVectorStatus(),
        entity.getOptimisticVersion(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  default List<TasteProfileDto> toDtos(List<TasteProfile> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<TasteProfileDto> result = new ArrayList<>(entities.size());
    for (TasteProfile entity : entities) {
      result.add(toDto(entity));
    }
    return result;
  }

  default TasteProfileVersionDto toVersionDto(TasteProfileVersion entity) {
    if (entity == null) {
      return null;
    }
    return new TasteProfileVersionDto(
        entity.getId(),
        entity.getTasteProfile() != null ? entity.getTasteProfile().getId() : null,
        entity.getDocumentVersion(),
        entity.getDocumentSnapshot(),
        entity.getFeedbackRangeStart(),
        entity.getFeedbackRangeEnd(),
        entity.getTrigger(),
        entity.getDeltasApplied(),
        entity.getModelTierUsed(),
        entity.getGeneratedAt());
  }

  default TasteProfileAuditEntryDto toAuditEntryDto(TasteProfileAuditLog entity) {
    if (entity == null) {
      return null;
    }
    return new TasteProfileAuditEntryDto(
        entity.getId(),
        entity.getActorUserId(),
        entity.getActorType(),
        entity.getChangeType(),
        entity.getPreviousDocumentVersion(),
        entity.getNewDocumentVersion(),
        entity.getSummary(),
        entity.getTraceId(),
        entity.getOccurredAt());
  }
}
