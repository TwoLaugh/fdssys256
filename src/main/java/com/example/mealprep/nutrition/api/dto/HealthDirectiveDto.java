package com.example.mealprep.nutrition.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for a single {@link com.example.mealprep.nutrition.domain.entity.HealthDirective}.
 * {@code optimisticVersion} carries the row's current {@code @Version}; callers echo it on accept /
 * reject to detect stale decisions.
 */
public record HealthDirectiveDto(
    UUID id,
    UUID userId,
    String externalDirectiveId,
    String sourcePlatform,
    Instant receivedAt,
    DirectiveStatus status,
    DirectiveType directiveType,
    String evidenceSummary,
    DirectiveConfidence evidenceConfidence,
    DirectiveInstructionDocument instruction,
    String mapsToModel,
    String mapsToTier,
    boolean temporary,
    Instant autoExpiresAt,
    Instant decidedAt,
    UUID decidedByUserId,
    DirectiveInstructionDocument userModification,
    String rejectionReason,
    SafetyGateVerdict safetyGateVerdict,
    List<SafetyFindingDto> safetyGateFindings,
    long optimisticVersion) {}
