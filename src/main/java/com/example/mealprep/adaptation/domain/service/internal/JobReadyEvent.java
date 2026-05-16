package com.example.mealprep.adaptation.domain.service.internal;

import java.util.UUID;

/**
 * Internal, package-private-ish event signalling that an enqueued {@code AdaptationJob} row is
 * ready for the async worker. Published {@code AFTER_COMMIT} from the four trigger entry methods on
 * {@code AdaptationServiceImpl} and consumed by {@link JobReadyEventListener}.
 *
 * <p>This event is intentionally local to the adaptation module — outside callers should never
 * subscribe to it; cross-module integration goes through the public {@code AdaptationEvent}
 * variants. Per LLD line 772.
 */
public record JobReadyEvent(UUID jobId) {}
