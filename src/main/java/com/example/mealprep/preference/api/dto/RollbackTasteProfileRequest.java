package com.example.mealprep.preference.api.dto;

import jakarta.validation.constraints.Min;

/**
 * Bound to {@code POST /api/v1/preferences/taste-profile/rollback}. Reverts the profile to {@code
 * targetDocumentVersion} (restored as a NEW monotonic version, never a decrement). {@code
 * expectedVersion} must equal the current entity {@code @Version} (mismatch → 409), so a rollback
 * into a concurrently-edited profile is rejected rather than silently clobbering the racing write.
 */
public record RollbackTasteProfileRequest(
    @Min(1) int targetDocumentVersion, @Min(0) long expectedVersion) {}
