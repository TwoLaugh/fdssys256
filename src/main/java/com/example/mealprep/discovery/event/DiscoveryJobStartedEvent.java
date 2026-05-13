package com.example.mealprep.discovery.event;

import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} from {@code DiscoveryServiceImpl.startJob}. The 01d runner listens
 * via {@code @Async @TransactionalEventListener} on this event to claim the job and run the state
 * machine. 01b publishes the event with no listener — Spring drops unhandled events, so this is
 * benign until 01d wires the runner.
 *
 * <p>Per LLD lines 479-483.
 */
public record DiscoveryJobStartedEvent(
    UUID jobId,
    UUID userId,
    DiscoveryJobTrigger trigger,
    int requestedCount,
    List<String> sourcesRequested,
    UUID traceId,
    Instant occurredAt) {}
