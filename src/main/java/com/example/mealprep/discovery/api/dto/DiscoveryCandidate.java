package com.example.mealprep.discovery.api.dto;

import java.util.Map;

/**
 * Lightweight candidate produced by {@code DiscoverySource.search}. The runner passes the whole
 * record back to {@code fetchRecipe}; {@code sourceMetadata} is opaque to the runner and exists so
 * a source can carry context (SERP rank, RSS publication date, etc.) from search to fetch without a
 * second round-trip. Per LLD line 277.
 */
public record DiscoveryCandidate(
    String sourceKey,
    String candidateUrl,
    String snippetTitle,
    String snippetDescription,
    Map<String, String> sourceMetadata) {}
