package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * In-process request carrying the data needed to archive one pruned taste-profile item. NOT exposed
 * via REST — the archive is written exclusively by the future {@code TasteProfileDeltaApplier} via
 * {@code PreferenceArchiveUpdateService.archiveItem}. Validation runs programmatically inside the
 * service (no controller, no implicit Jakarta-on-controller path).
 */
public record ArchiveItemRequest(
    @NotBlank @Size(max = 128) String fieldPath,
    @NotBlank @Size(max = 128) String itemKey,
    @NotNull JsonNode itemPayload,
    @Min(0) int evidenceCount,
    @Nullable LocalDate lastSignalAt,
    @NotNull ArchiveReason reason) {}
