package com.example.mealprep.discovery.event;

import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} from the 01d runner at terminal-state transition (or from the
 * orphan-sweep finaliser). Carries the job summary so downstream subscribers can react without
 * re-reading the row.
 *
 * <p>Per LLD lines 485-491.
 */
public record DiscoveryJobCompletedEvent(
    UUID jobId,
    UUID userId,
    DiscoveryJobStatus terminalStatus,
    int recipesIngested,
    int candidatesSeen,
    List<String> sourcesSucceeded,
    List<String> sourcesFailed,
    String errorSummary,
    UUID traceId,
    Instant occurredAt) {}
