package com.example.mealprep.notification.domain.service.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * Package-private dispatch bus. Event listeners route drafts through this; it owns preference +
 * quiet-hours filtering, debouncing / bundling, persistence (via {@code
 * NotificationUpdateService.create}), fan-out to {@code DeliveryChannel} impls, and {@code
 * NotificationCreatedEvent} publication. Not part of the public module surface.
 */
interface NotificationDispatcher {

  /**
   * Dispatch a draft. Returns the id of the persisted (or bundled-into) notification, or {@link
   * Optional#empty()} when the draft was suppressed (preference OFF or quiet hours).
   */
  Optional<UUID> dispatch(NotificationDraft draft);
}
