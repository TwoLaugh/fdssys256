package com.example.mealprep.discovery.api.dto;

import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Cross-module read shape for {@code DiscoveryJob}. Mirrors LLD lines 215-225. */
public record DiscoveryJobDto(
    UUID id,
    UUID userId,
    DiscoveryJobTrigger trigger,
    int requestedCount,
    DiscoveryConstraints constraints,
    List<String> sourcesRequested,
    DiscoveryJobStatus status,
    Instant queuedAt,
    Instant startedAt,
    Instant completedAt,
    int candidatesSeen,
    int candidatesAfterFilter,
    int recipesIngested,
    int recipesSkippedDuplicate,
    List<String> sourcesSucceeded,
    List<String> sourcesFailed,
    String errorSummary,
    UUID traceId,
    long optimisticVersion) {}
