package com.example.mealprep.discovery.api.dto;

import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module read shape for {@code DiscoverySource}. Mirrors LLD lines 206-213; only operational
 * fields needed by admin / debug callers are exposed (no {@code crawlConfig} JSONB, no {@code
 * qualityScore} until v2).
 */
public record DiscoverySourceDto(
    UUID id,
    String sourceKey,
    String displayName,
    DiscoverySourceKind kind,
    String baseUrl,
    boolean enabled,
    int requestsPerMinute,
    int requestsPerDay,
    boolean respectRobotsTxt,
    String userAgent,
    int failureStreak,
    Instant lastFailureAt,
    Instant lastSuccessAt,
    String notes,
    long optimisticVersion) {}
