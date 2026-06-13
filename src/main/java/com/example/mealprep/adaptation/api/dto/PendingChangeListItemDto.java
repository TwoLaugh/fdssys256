package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * List-row projection of a pending change. Returned by {@code listPendingForUser} (the top-3
 * surface) and {@code listPendingHistoryForRecipe}. {@code reasoningPreview} is a server-side
 * truncation (max 200 chars; see {@link
 * com.example.mealprep.adaptation.api.mapper.PendingChangeMapper#truncateReasoning(String)}).
 *
 * <p>{@code status}, {@code resolvedAt}, and {@code optimisticVersion} (ticket frontend-gaps:
 * adaptation-pending-change-list-dto) ride on the list row so both surfaces render lifecycle
 * without a follow-up single-row read per card. The top-3 read ({@code listPendingForUser}) returns
 * only PENDING rows ({@code resolvedAt == null}); the pending-history read ({@code
 * listPendingHistoryForRecipe}) spans the full lifecycle, including {@code EXPIRED}/{@code
 * SUPERSEDED}. {@code optimisticVersion} lets an accept/reject call carry an {@code
 * expectedVersion} straight from the list without expanding the row first. All three are already
 * loaded on the {@code PendingChange} entity the list queries select, so they add no extra query —
 * the projection stays single-select.
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
    Instant expiresAt,
    PendingChangeStatus status,
    Instant resolvedAt,
    long optimisticVersion) {}
