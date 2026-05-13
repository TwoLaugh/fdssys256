package com.example.mealprep.adaptation.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/adaptation/pending-changes/{id}/reject}.
 *
 * <p>{@code reasonNote} is optional ({@link Size}-bounded but not {@code @NotBlank}) — the user may
 * dismiss with no comment, or supply a short string to feed reasoning back into prompt-quality
 * dashboards. Longer reasoning belongs in a feedback flow, not a reject-note.
 *
 * <p>Per LLD §DTOs line 377; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record RejectPendingChangeRequest(@Nullable @Size(max = 200) String reasonNote) {}
