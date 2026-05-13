package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public-facing view of a clarification-query row. */
public record ClarificationQueryDto(
    UUID id,
    UUID feedbackEntryId,
    String questionText,
    List<ClarificationOptionDto> options,
    ClarificationStatus status,
    Instant expiresAt,
    Instant createdAt) {}
