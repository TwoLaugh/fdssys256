package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a user's taste profile. {@code optimisticVersion} is the JPA {@code @Version} value
 * the client must echo back as {@code expectedVersion} on a subsequent PUT; {@code documentVersion}
 * is the monotonic AI delta-batch counter, exposed read-only.
 */
public record TasteProfileDto(
    UUID id,
    UUID userId,
    TasteProfileDocument document,
    int documentVersion,
    String feedbackCursor,
    int basedOnFeedbackCount,
    Instant lastDeltaAppliedAt,
    Integer lastTokenEstimate,
    TasteVectorStatus tasteVectorStatus,
    long optimisticVersion,
    Instant createdAt,
    Instant updatedAt) {}
