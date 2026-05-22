package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read shape of a single archived (pruned) taste-profile item. Returned by the user-facing archive
 * endpoint and by {@code getFullArchive} (consumed by the future AI delta-update task to detect
 * re-emerging preferences). {@code rePromotedAt} is non-null once the item has been lifted back
 * into the live profile.
 */
public record PreferenceArchiveEntryDto(
    UUID id,
    UUID userId,
    String fieldPath,
    String itemKey,
    JsonNode itemPayload,
    int evidenceCount,
    LocalDate lastSignalAt,
    Instant archivedAt,
    ArchiveReason archivedReason,
    Instant rePromotedAt) {}
