package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.notification.api.dto.CreateNotificationRequest;
import com.example.mealprep.notification.api.dto.DeliveryLogEntryDto;
import com.example.mealprep.notification.api.dto.NotificationDto;
import com.example.mealprep.notification.api.dto.NotificationListFilter;
import com.example.mealprep.notification.api.dto.NotificationPreferenceDto;
import com.example.mealprep.notification.api.dto.NotificationSummaryDto;
import com.example.mealprep.notification.api.dto.UpdateNotificationPreferenceRequest;
import com.example.mealprep.notification.api.mapper.DeliveryLogMapper;
import com.example.mealprep.notification.api.mapper.NotificationMapper;
import com.example.mealprep.notification.api.mapper.NotificationPreferenceMapper;
import com.example.mealprep.notification.config.NotificationProperties;
import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPreference;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import com.example.mealprep.notification.domain.repository.DeliveryLogRepository;
import com.example.mealprep.notification.domain.repository.NotificationPreferenceRepository;
import com.example.mealprep.notification.domain.repository.NotificationRepository;
import com.example.mealprep.notification.domain.service.NotificationQueryService;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.notification.exception.NotificationNotFoundException;
import com.example.mealprep.notification.exception.NotificationPreferenceNotFoundException;
import com.example.mealprep.notification.exception.NotificationStateTransitionException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of both {@link NotificationQueryService} and {@link
 * NotificationUpdateService}. Reads run {@code readOnly}; writes run REQUIRED. Enforces the status
 * state machine on the {@code markX} methods.
 */
@Service
public class NotificationServiceImpl
    implements NotificationQueryService, NotificationUpdateService {

  private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

  private final NotificationRepository notificationRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final NotificationMapper notificationMapper;
  private final NotificationPreferenceMapper preferenceMapper;
  private final DeliveryLogMapper deliveryLogMapper;
  private final DeliveryLogRepository deliveryLogRepository;
  private final NotificationProperties properties;
  private final Clock clock;

  public NotificationServiceImpl(
      NotificationRepository notificationRepository,
      NotificationPreferenceRepository preferenceRepository,
      NotificationMapper notificationMapper,
      NotificationPreferenceMapper preferenceMapper,
      DeliveryLogMapper deliveryLogMapper,
      DeliveryLogRepository deliveryLogRepository,
      NotificationProperties properties,
      Clock clock) {
    this.notificationRepository = notificationRepository;
    this.preferenceRepository = preferenceRepository;
    this.notificationMapper = notificationMapper;
    this.preferenceMapper = preferenceMapper;
    this.deliveryLogMapper = deliveryLogMapper;
    this.deliveryLogRepository = deliveryLogRepository;
    this.properties = properties;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<NotificationDto> getById(UUID userId, UUID notificationId) {
    return notificationRepository
        .findByIdAndUserId(notificationId, userId)
        .map(notificationMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<NotificationDto> getByIds(UUID userId, List<UUID> notificationIds) {
    if (notificationIds == null || notificationIds.isEmpty()) {
      return List.of();
    }
    List<NotificationDto> result = new ArrayList<>(notificationIds.size());
    for (UUID id : notificationIds) {
      notificationRepository
          .findByIdAndUserId(id, userId)
          .map(notificationMapper::toDto)
          .ifPresent(result::add);
    }
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<NotificationDto> list(UUID userId, NotificationListFilter filter, Pageable pageable) {
    Set<NotificationStatus> statuses =
        filter == null || filter.statuses() == null || filter.statuses().isEmpty()
            ? null
            : filter.statuses();
    Set<NotificationKind> kinds =
        filter == null || filter.kinds() == null || filter.kinds().isEmpty()
            ? null
            : filter.kinds();
    Instant since = filter == null ? null : filter.since();
    return notificationRepository
        .search(userId, statuses, kinds, since, pageable)
        .map(notificationMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public NotificationSummaryDto getSummary(UUID userId) {
    int unread =
        (int) notificationRepository.countByUserIdAndStatus(userId, NotificationStatus.UNREAD);
    int attention =
        (int)
            notificationRepository.countByUserIdAndStatusAndSeverity(
                userId, NotificationStatus.UNREAD, NotificationSeverity.ATTENTION);
    int urgent =
        (int)
            notificationRepository.countByUserIdAndStatusAndSeverity(
                userId, NotificationStatus.UNREAD, NotificationSeverity.URGENT);
    return new NotificationSummaryDto(unread, attention, urgent, Instant.now(clock));
  }

  @Override
  @Transactional(readOnly = true)
  public NotificationPreferenceDto getPreferences(UUID userId) {
    return preferenceRepository
        .findByUserId(userId)
        .map(preferenceMapper::toDto)
        .orElseThrow(() -> new NotificationPreferenceNotFoundException(userId));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<DeliveryLogEntryDto> getDeliveryLog(
      UUID userId, UUID notificationId, Pageable pageable) {
    notificationRepository
        .findByIdAndUserId(notificationId, userId)
        .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    return deliveryLogRepository
        .findByNotificationIdOrderByAttemptedAtDesc(notificationId, pageable)
        .map(deliveryLogMapper::toDto);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public NotificationDto create(CreateNotificationRequest request) {
    Notification notification =
        Notification.builder()
            .id(UUID.randomUUID())
            .userId(request.userId())
            .householdId(request.householdId())
            .kind(request.kind())
            .severity(request.severity())
            .title(request.title())
            .body(request.body())
            .payload(request.payload())
            .status(NotificationStatus.UNREAD)
            .actionTargetUri(request.actionTargetUri())
            .bundleCount(1)
            .sourceEventId(request.sourceEventId())
            .traceId(request.traceId())
            .build();
    Notification saved = notificationRepository.save(notification);
    log.info(
        "notification created id={} userId={} kind={} severity={}",
        saved.getId(),
        saved.getUserId(),
        saved.getKind(),
        saved.getSeverity());
    return notificationMapper.toDto(saved);
  }

  @Override
  @Transactional
  public NotificationDto markRead(UUID userId, UUID notificationId) {
    return transition(userId, notificationId, NotificationStatus.READ);
  }

  @Override
  @Transactional
  public NotificationDto markDismissed(UUID userId, UUID notificationId) {
    return transition(userId, notificationId, NotificationStatus.DISMISSED);
  }

  @Override
  @Transactional
  public NotificationDto markActioned(UUID userId, UUID notificationId) {
    return transition(userId, notificationId, NotificationStatus.ACTIONED);
  }

  private NotificationDto transition(UUID userId, UUID notificationId, NotificationStatus target) {
    Notification notification =
        notificationRepository
            .findByIdAndUserId(notificationId, userId)
            .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    NotificationStatus from = notification.getStatus();
    if (!isLegalTransition(from, target)) {
      throw new NotificationStateTransitionException(from, target);
    }
    Instant now = Instant.now(clock);
    notification.setStatus(target);
    switch (target) {
      case READ -> notification.setReadAt(now);
      case ACTIONED -> notification.setActionedAt(now);
      case DISMISSED -> notification.setDismissedAt(now);
      default -> throw new IllegalStateException("unexpected target status " + target);
    }
    return notificationMapper.toDto(notificationRepository.save(notification));
  }

  /** Legal transitions per {@code lld/notification.md} §Status state machine. */
  static boolean isLegalTransition(NotificationStatus from, NotificationStatus to) {
    return switch (from) {
      case UNREAD ->
          to == NotificationStatus.READ
              || to == NotificationStatus.DISMISSED
              || to == NotificationStatus.ACTIONED;
      case READ -> to == NotificationStatus.DISMISSED || to == NotificationStatus.ACTIONED;
      case ACTIONED -> to == NotificationStatus.DISMISSED;
      case DISMISSED -> false;
    };
  }

  @Override
  @Transactional
  public int markAllRead(UUID userId, Set<NotificationKind> kinds) {
    Instant now = Instant.now(clock);
    if (kinds == null || kinds.isEmpty()) {
      return notificationRepository.markAllReadForUser(userId, now);
    }
    return notificationRepository.markReadForUserAndKinds(userId, kinds, now);
  }

  @Override
  @Transactional
  public NotificationPreferenceDto updatePreferences(
      UUID userId, UpdateNotificationPreferenceRequest request) {
    NotificationPreference preference =
        preferenceRepository
            .findByUserId(userId)
            .orElseGet(() -> newDefaultPreference(userId, Instant.now(clock)));

    // Optimistic-lock pre-check (mirrors the preference module's pattern).
    if (preference.getOptimisticVersion() != request.expectedVersion()) {
      throw new ObjectOptimisticLockingFailureException(
          NotificationPreference.class, preference.getId());
    }

    // The enabled-kinds map must cover exactly the NotificationKind enum.
    Set<NotificationKind> requestedKinds = EnumSet.noneOf(NotificationKind.class);
    requestedKinds.addAll(request.enabledKinds().keySet());
    if (!requestedKinds.equals(EnumSet.allOf(NotificationKind.class))) {
      throw new IllegalArgumentException(
          "enabledKinds must contain exactly the NotificationKind enum values");
    }

    preference.setEnabledKinds(new java.util.EnumMap<>(request.enabledKinds()));
    preference.setQuietHoursEnabled(request.quietHoursEnabled());
    preference.setQuietHoursStart(request.quietHoursStart());
    preference.setQuietHoursEnd(request.quietHoursEnd());
    preference.setTimezone(request.timezone());
    preference.setDebounceWindowMinutes(request.debounceWindowMinutes());

    NotificationPreference saved = preferenceRepository.saveAndFlush(preference);
    log.info(
        "notification preferences updated userId={} version={}",
        userId,
        saved.getOptimisticVersion());
    return preferenceMapper.toDto(saved);
  }

  @Override
  @Transactional
  public NotificationPreferenceDto ensurePreferencesForUser(UUID userId) {
    Optional<NotificationPreference> existing = preferenceRepository.findByUserId(userId);
    if (existing.isPresent()) {
      return preferenceMapper.toDto(existing.get());
    }
    NotificationPreference created =
        preferenceRepository.save(newDefaultPreference(userId, Instant.now(clock)));
    log.info("notification preferences seeded userId={} preferenceId={}", userId, created.getId());
    return preferenceMapper.toDto(created);
  }

  private NotificationPreference newDefaultPreference(UUID userId, Instant now) {
    return NotificationPreference.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .enabledKinds(
            NotificationDefaults.defaultEnabledKinds(properties.planGeneratedDefaultEnabled()))
        .quietHoursEnabled(false)
        .quietHoursStart(null)
        .quietHoursEnd(null)
        .timezone(NotificationDefaults.DEFAULT_TIMEZONE)
        .debounceWindowMinutes(properties.defaultDebounceWindowMinutes())
        .build();
  }
}
