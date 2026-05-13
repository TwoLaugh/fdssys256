package com.example.mealprep.adaptation.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;

/**
 * Request body for {@code POST /api/v1/adaptation/pending-changes/{id}/accept}.
 *
 * <p>{@code expectedOptimisticVersion} guards against the two-tabs race: if the user opened the
 * pending change on tab A, modified it, and then accepted on tab B with stale state, the optimistic
 * version mismatch returns 409 — and the controller surfaces the canonical {@link PendingChangeDto}
 * so the UI can re-render.
 *
 * <p>{@code userEdits} is the optional diff overlay — when present, the service-layer
 * {@code @ValidRecipeDiff} validator (01d) asserts the diff still references the same {@code
 * baseVersionId} and ingredient mapping keys exist in the catalogue. 01b ships only the carrying
 * shape.
 *
 * <p>Per LLD §DTOs lines 375-376; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record AcceptPendingChangeRequest(
    @Nullable @Valid JsonNode userEdits, long expectedOptimisticVersion) {}
