package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * List-row projection of a pending change. Returned by {@code listPendingForUser} (the top-3
 * surface) and {@code listPendingHistoryForRecipe}. {@code reasoningPreview} is a server-side
 * truncation (max 200 chars; see {@link
 * com.example.mealprep.adaptation.api.mapper.PendingChangeMapper#truncateReasoning(String)}).
 *
 * <p>Per LLD §DTOs lines 371-373; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record PendingChangeListItemDto(
    UUID id,
    UUID recipeId,
    ChangeDimension changeDimension,
    String reasoningPreview,
    BigDecimal confidence,
    BigDecimal impactScore,
    Instant createdAt,
    Instant expiresAt) {}
