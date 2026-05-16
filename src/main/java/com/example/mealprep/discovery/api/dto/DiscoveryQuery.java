package com.example.mealprep.discovery.api.dto;

/**
 * Search input passed to {@code DiscoverySource.search}. The frozen {@link DiscoveryConstraints}
 * snapshot, the per-source result cap, and the User-Agent the runner expects the source to use on
 * outbound requests. Per LLD line 275 verbatim.
 */
public record DiscoveryQuery(DiscoveryConstraints constraints, int maxResults, String userAgent) {}
