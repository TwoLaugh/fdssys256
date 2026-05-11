package com.example.mealprep.provisions.api.dto;

import java.time.Instant;

/**
 * Staleness metadata for the planner-bundle snapshot. {@code supplierCacheCoverageBps} is the share
 * (in basis points, 0..10000 inclusive) of requested ingredient mapping keys that have at least one
 * supplier-product row in the cache. {@code inRampUpWindow} is {@code true} when the caller's
 * household membership is younger than 56 days (per HLD ramp-up grace period). {@code generatedAt}
 * is captured at the end of resolution and used by the planner for downstream cache-staleness
 * checks.
 */
public record BundleStaleness(
    int supplierCacheCoverageBps, boolean inRampUpWindow, Instant generatedAt) {}
