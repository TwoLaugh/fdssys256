package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Full pending-change projection. Used by the per-id read path and by accept / reject responses.
 *
 * <p>{@code supersededBy}, {@code acceptedVersionId}, {@code userEdits}, and {@code resolvedAt} are
 * nullable — populated only for resolved rows; {@code expiresAt} is always populated since 01a
 * enforces a non-null expiry at insert.
 *
 * <p>Per LLD §DTOs lines 360-369; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record PendingChangeDto(
    UUID id,
    UUID recipeId,
    UUID userId,
    UUID jobId,
    UUID traceId,
    ChangeDimension changeDimension,
    AdaptationClassification proposedClassification,
    UUID baseVersionId,
    UUID baseBranchId,
    JsonNode proposedDiff,
    String reasoning,
    String nutritionalNotes,
    BigDecimal confidence,
    BigDecimal impactScore,
    String promptTemplateVersion,
    PendingChangeStatus status,
    @Nullable UUID supersededBy,
    @Nullable UUID acceptedVersionId,
    @Nullable JsonNode userEdits,
    Instant createdAt,
    Instant expiresAt,
    @Nullable Instant resolvedAt,
    long optimisticVersion) {}
