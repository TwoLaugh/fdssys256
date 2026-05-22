package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.notification.api.dto.NotificationDto;
import com.example.mealprep.notification.domain.entity.DeliveryLog;
import com.example.mealprep.notification.domain.entity.DeliveryOutcome;
import com.example.mealprep.notification.domain.entity.DeliverySkipReason;
import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.entity.NotificationPreference;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import com.example.mealprep.notification.domain.repository.DeliveryLogRepository;
import com.example.mealprep.notification.domain.repository.NotificationPreferenceRepository;
import com.example.mealprep.notification.domain.repository.NotificationRepository;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.notification.domain.service.internal.delivery.DeliveryChannel;
import com.example.mealprep.notification.domain.service.internal.delivery.DeliveryChannelRegistry;
import com.example.mealprep.notification.event.NotificationCreatedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link NotificationDispatcher}. Runs in its own transaction (listeners invoke it {@code
 * AFTER_COMMIT}, after the producer's transaction has already committed). Implements flows F8
 * (filter) and F9 (debounce) plus persistence, fan-out and event publication per {@code
 * lld/notification.md}.
 */
@Service
class NotificationDispatcherImpl implements NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcherImpl.class);

  private final NotificationUpdateService updateService;
  private final NotificationRepository notificationRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final DeliveryLogRepository deliveryLogRepository;
  private final QuietHoursEvaluator quietHoursEvaluator;
  private final NotificationDebouncer debouncer;
  private final DeliveryChannelRegistry channelRegistry;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  NotificationDispatcherImpl(
      NotificationUpdateService updateService,
      NotificationRepository notificationRepository,
      NotificationPreferenceRepository preferenceRepository,
      DeliveryLogRepository deliveryLogRepository,
      QuietHoursEvaluator quietHoursEvaluator,
      NotificationDebouncer debouncer,
      DeliveryChannelRegistry channelRegistry,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.updateService = updateService;
    this.notificationRepository = notificationRepository;
    this.preferenceRepository = preferenceRepository;
    this.deliveryLogRepository = deliveryLogRepository;
    this.quietHoursEvaluator = quietHoursEvaluator;
    this.debouncer = debouncer;
    this.channelRegistry = channelRegistry;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  @Override
  @Transactional
  public Optional<UUID> dispatch(NotificationDraft draft) {
    Instant now = Instant.now(clock);

    // Ensure a preference row exists (idempotent), then load it for filtering.
    updateService.ensurePreferencesForUser(draft.userId());
    NotificationPreference preference =
        preferenceRepository
            .findByUserId(draft.userId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "preferences missing after ensure for user=" + draft.userId()));

    // F8 — preference + quiet-hours filter.
    QuietHoursEvaluator.Decision decision =
        quietHoursEvaluator.evaluate(draft.kind(), draft.severity(), preference, now);
    if (!decision.deliver()) {
      // Open-question resolution: persist a dismissed-on-arrival row so the skip log has an FK
      // target and the audit trail is complete; the row is never user-visible.
      Notification suppressed = persistSuppressed(draft, now);
      writeDeliveryLog(
          suppressed,
          DeliveryChannel.Channel.IN_APP,
          DeliveryOutcome.SKIPPED,
          decision.skipReason(),
          now);
      return Optional.empty();
    }

    // F9 — debounce / bundle.
    Notification bundleTarget =
        debouncer.findBundleTarget(
            draft.userId(),
            draft.kind(),
            draft.bundlingKey(),
            preference.getDebounceWindowMinutes(),
            now);
    if (bundleTarget != null) {
      debouncer.applyBundle(bundleTarget, draft, now);
      notificationRepository.save(bundleTarget);
      writeDeliveryLog(
          bundleTarget,
          DeliveryChannel.Channel.IN_APP,
          DeliveryOutcome.SKIPPED,
          DeliverySkipReason.DEDUPED_INTO_BUNDLE,
          now);
      return Optional.of(bundleTarget.getId());
    }

    // Persist a fresh notification via the public create seam.
    NotificationDto created = updateService.create(draft.toCreateRequest());
    Notification persisted =
        notificationRepository
            .findById(created.id())
            .orElseThrow(() -> new IllegalStateException("notification vanished after create"));

    // Fan out across the registered channels — one delivery-log row per channel.
    boolean anyDelivered = false;
    for (DeliveryChannel channel : channelRegistry.channelsFor(persisted)) {
      DeliveryOutcome outcome = channel.deliver(persisted);
      writeDeliveryLog(persisted, channel.channel(), outcome, null, now);
      anyDelivered = anyDelivered || outcome == DeliveryOutcome.DELIVERED;
    }
    if (!anyDelivered) {
      log.warn(
          "no channel delivered notification id={} kind={}",
          persisted.getId(),
          persisted.getKind());
    }

    // Publish the (deferred-listener) creation event — once per persisted notification.
    eventPublisher.publishEvent(
        new NotificationCreatedEvent(
            persisted.getId(),
            persisted.getUserId(),
            persisted.getHouseholdId(),
            persisted.getKind(),
            persisted.getSeverity(),
            false,
            draft.origin(),
            draft.originTrace(),
            persisted.getTraceId(),
            now));

    return Optional.of(persisted.getId());
  }

  private Notification persistSuppressed(NotificationDraft draft, Instant now) {
    Notification notification =
        Notification.builder()
            .id(UUID.randomUUID())
            .userId(draft.userId())
            .householdId(draft.householdId())
            .kind(draft.kind())
            .severity(draft.severity())
            .title(draft.title())
            .body(draft.body())
            .payload(draft.payload())
            .status(NotificationStatus.DISMISSED)
            .actionTargetUri(draft.actionTargetUri())
            .bundleCount(1)
            .sourceEventId(draft.sourceEventId())
            .traceId(draft.traceId())
            .dismissedAt(now)
            .build();
    return notificationRepository.save(notification);
  }

  private void writeDeliveryLog(
      Notification notification,
      DeliveryChannel.Channel channel,
      DeliveryOutcome outcome,
      DeliverySkipReason skipReason,
      Instant now) {
    deliveryLogRepository.save(
        DeliveryLog.builder()
            .id(UUID.randomUUID())
            .notification(notification)
            .channel(channel)
            .outcome(outcome)
            .skipReason(skipReason)
            .attemptedAt(now)
            .build());
  }
}
