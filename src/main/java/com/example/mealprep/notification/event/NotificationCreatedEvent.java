package com.example.mealprep.notification.event;

import com.example.mealprep.core.events.OriginAwareEvent;
import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import java.time.Instant;
import java.util.UUID;

/**
 * Published from the dispatcher's transaction once per persisted notification (never on
 * suppression). No v1 listeners — this is the stable contract a future realtime delivery channel
 * (WebSocket / SSE) listens to, deferred per {@code lld/notification.md} §Out of Scope.
 *
 * <p>Implements {@link OriginAwareEvent} (per {@code tickets/core/02b}): {@code origin()} reflects
 * what produced the underlying domain event. For v1 the dispatcher tags created notifications with
 * the supplied origin (defaulting to {@link Origin#USER}); future bridge-driven notifications carry
 * {@code AI_FEEDBACK} etc.
 */
public record NotificationCreatedEvent(
    UUID notificationId,
    UUID userId,
    UUID householdId,
    NotificationKind kind,
    NotificationSeverity severity,
    boolean wasBundled,
    Origin origin,
    String originTrace,
    UUID traceId,
    Instant occurredAt)
    implements OriginAwareEvent {}
