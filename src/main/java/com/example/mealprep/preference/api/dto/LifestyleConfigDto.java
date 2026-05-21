package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a user's lifestyle-config aggregate. {@code optimisticVersion} is the JPA
 * {@code @Version} the client must echo back as {@code expectedVersion} on a subsequent PUT.
 */
public record LifestyleConfigDto(
    UUID id,
    UUID userId,
    LifestyleConfigDocument document,
    Instant lastReviewPromptAt,
    long optimisticVersion,
    Instant createdAt,
    Instant updatedAt) {}
