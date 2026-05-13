package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-side projection of {@link com.example.mealprep.adaptation.domain.entity.AdaptationJob}. The
 * full lifecycle snapshot — status, source, priority, approval-policy, timing — surfaced to admin
 * dashboards and the run-history controllers.
 *
 * <p>Per LLD §DTOs lines 292-298; field order is verbatim with the entity.
 */
public record AdaptationJobDto(
    UUID id,
    UUID recipeId,
    UUID userId,
    Catalogue catalogue,
    JobSource source,
    JobPriority priority,
    ApprovalPolicy approvalPolicy,
    JobStatus status,
    JobFailureReason failureReason,
    String failureExcerpt,
    JsonNode inputs,
    String promptTemplateVersion,
    UUID traceId,
    UUID parentDecisionId,
    Instant enqueuedAt,
    Instant startedAt,
    Instant completedAt,
    Integer durationMs,
    long optimisticVersion) {}
