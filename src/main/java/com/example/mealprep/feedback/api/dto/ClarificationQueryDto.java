package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public-facing view of a clarification-query row. {@code textExcerpt} is the leading 160 code
 * points of the originating entry's {@code text} (plain truncation, no ellipsis marker) so the
 * inbox can render its "from: …" context quote without a per-card {@code GET /feedback/{id}}
 * (frontend-gaps: feedback-clarification-text-excerpt).
 */
public record ClarificationQueryDto(
    UUID id,
    UUID feedbackEntryId,
    String textExcerpt,
    String questionText,
    List<ClarificationOptionDto> options,
    ClarificationStatus status,
    Instant expiresAt,
    Instant createdAt) {}
