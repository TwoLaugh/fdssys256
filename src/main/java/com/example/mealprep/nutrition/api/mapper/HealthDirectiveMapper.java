package com.example.mealprep.nutrition.api.mapper;

import com.example.mealprep.nutrition.api.dto.HealthDirectiveDto;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import org.mapstruct.Mapper;

/** Entity ↔ DTO mapping for {@link HealthDirective}. */
@Mapper(componentModel = "spring")
public interface HealthDirectiveMapper {

  default HealthDirectiveDto toDto(HealthDirective entity) {
    if (entity == null) {
      return null;
    }
    return new HealthDirectiveDto(
        entity.getId(),
        entity.getUserId(),
        entity.getExternalDirectiveId(),
        entity.getSourcePlatform(),
        entity.getReceivedAt(),
        entity.getStatus(),
        entity.getDirectiveType(),
        entity.getEvidenceSummary(),
        entity.getEvidenceConfidence(),
        entity.getInstructionPayload(),
        entity.getMapsToModel(),
        entity.getMapsToTier(),
        entity.isTemporary(),
        entity.getAutoExpiresAt(),
        entity.getDecidedAt(),
        entity.getDecidedByUserId(),
        entity.getUserModificationJson(),
        entity.getRejectionReason(),
        entity.getSafetyGateVerdict(),
        entity.getSafetyGateFindings(),
        entity.getOptimisticVersion());
  }
}
