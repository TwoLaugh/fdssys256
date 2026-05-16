package com.example.mealprep.discovery.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} from the 01d runner per successful recipe ingest. Carries the
 * discovery-journey provenance (jobId, sourceKey, canonicalUrl, traceId) that downstream
 * subscribers (notifications, admin telemetry) need to attribute the ingest back to the discovery
 * job; the recipe module's parallel {@code RecipeCreatedEvent} fires from the {@code
 * RecipeWriteApi} call site and carries the recipe-shape provenance.
 *
 * <p>Per LLD lines 493-498.
 */
public record DiscoveryRecipeIngestedEvent(
    UUID jobId,
    UUID userId,
    UUID recipeId,
    String sourceKey,
    String canonicalUrl,
    BigDecimal extractionConfidence,
    UUID traceId,
    Instant occurredAt) {}
