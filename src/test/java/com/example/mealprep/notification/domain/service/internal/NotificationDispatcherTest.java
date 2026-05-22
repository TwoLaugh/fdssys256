package com.example.mealprep.notification.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.notification.api.dto.NotificationDto;
import com.example.mealprep.notification.config.NotificationProperties;
import com.example.mealprep.notification.domain.entity.DeliveryLog;
import com.example.mealprep.notification.domain.entity.DeliveryOutcome;
import com.example.mealprep.notification.domain.entity.DeliverySkipReason;
import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationPreference;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import com.example.mealprep.notification.domain.repository.DeliveryLogRepository;
import com.example.mealprep.notification.domain.repository.NotificationPreferenceRepository;
import com.example.mealprep.notification.domain.repository.NotificationRepository;
import com.example.mealprep.notification.domain.service.internal.delivery.DeliveryChannelRegistry;
import com.example.mealprep.notification.domain.service.internal.delivery.InAppDeliveryChannel;
import com.example.mealprep.notification.event.NotificationCreatedEvent;
import com.example.mealprep.notification.testdata.NotificationTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

  @Mock private NotificationServiceImpl notificationService;
  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationPreferenceRepository preferenceRepository;
  @Mock private DeliveryLogRepository deliveryLogRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private NotificationDispatcherImpl dispatcher;
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    QuietHoursEvaluator evaluator = new QuietHoursEvaluator(clock);
    NotificationDebouncer debouncer =
        new NotificationDebouncer(notificationRepository, new ObjectMapper());
    DeliveryChannelRegistry registry =
        new DeliveryChannelRegistry(List.of(new InAppDeliveryChannel()));
    dispatcher =
        new NotificationDispatcherImpl(
            notificationService,
            notificationRepository,
            preferenceRepository,
            deliveryLogRepository,
            evaluator,
            debouncer,
            registry,
            eventPublisher,
            new NotificationProperties(null, null, null),
            clock);
  }

  private NotificationDraft draft(UUID user, NotificationKind kind, NotificationSeverity severity) {
    NotificationPayload payload =
        new NotificationPayload.ItemNearExpiryPayload(kind, List.of(), LocalDate.now(), 0);
    return TestDrafts.create(user, kind, severity, null, payload);
  }

  @Test
  void dispatch_disabledKind_suppressesAndLogsSkip_noEvent() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.preference(user);
    pref.getEnabledKinds().put(NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, false);
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.of(pref));
    when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Optional<UUID> result =
        dispatcher.dispatch(
            draft(
                user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, NotificationSeverity.ATTENTION));

    assertThat(result).isEmpty();
    ArgumentCaptor<DeliveryLog> logCaptor = ArgumentCaptor.forClass(DeliveryLog.class);
    verify(deliveryLogRepository).save(logCaptor.capture());
    assertThat(logCaptor.getValue().getOutcome()).isEqualTo(DeliveryOutcome.SKIPPED);
    assertThat(logCaptor.getValue().getSkipReason()).isEqualTo(DeliverySkipReason.DISABLED_BY_PREF);
    verify(eventPublisher, never()).publishEvent(any(NotificationCreatedEvent.class));
    verify(notificationService, never()).create(any());
  }

  @Test
  void dispatch_quietHours_suppresses_persistsDismissedRow() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.quietHoursPreference(user, "00:00", "23:59");
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.of(pref));
    when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Optional<UUID> result =
        dispatcher.dispatch(
            draft(
                user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, NotificationSeverity.ATTENTION));

    assertThat(result).isEmpty();
    ArgumentCaptor<Notification> notifCaptor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(notifCaptor.capture());
    assertThat(notifCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.DISMISSED);
    assertThat(notifCaptor.getValue().getDismissedAt()).isNotNull();
    verify(eventPublisher, never()).publishEvent(any(NotificationCreatedEvent.class));
  }

  @Test
  void dispatch_urgentDuringQuietHours_delivers_publishesEvent() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.quietHoursPreference(user, "00:00", "23:59");
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.of(pref));
    when(notificationRepository.findOpenForBundling(any(), any(), any(), any()))
        .thenReturn(List.of());
    Notification created =
        NotificationTestData.notification(
            user, NotificationKind.HEALTH_DIRECTIVE_RECEIVED, NotificationStatus.UNREAD);
    when(notificationService.create(any())).thenReturn(toDto(created));
    when(notificationRepository.findById(created.getId())).thenReturn(Optional.of(created));

    Optional<UUID> result =
        dispatcher.dispatch(
            draft(user, NotificationKind.HEALTH_DIRECTIVE_RECEIVED, NotificationSeverity.URGENT));

    assertThat(result).contains(created.getId());
    verify(eventPublisher, times(1)).publishEvent(any(NotificationCreatedEvent.class));
  }

  @Test
  void dispatch_freshDelivery_writesDeliveredLog_andPublishesOnce() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.preference(user);
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.of(pref));
    when(notificationRepository.findOpenForBundling(any(), any(), any(), any()))
        .thenReturn(List.of());
    Notification created =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, NotificationStatus.UNREAD);
    when(notificationService.create(any())).thenReturn(toDto(created));
    when(notificationRepository.findById(created.getId())).thenReturn(Optional.of(created));

    Optional<UUID> result =
        dispatcher.dispatch(
            draft(
                user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, NotificationSeverity.ATTENTION));

    assertThat(result).contains(created.getId());
    ArgumentCaptor<DeliveryLog> logCaptor = ArgumentCaptor.forClass(DeliveryLog.class);
    verify(deliveryLogRepository).save(logCaptor.capture());
    assertThat(logCaptor.getValue().getOutcome()).isEqualTo(DeliveryOutcome.DELIVERED);
    verify(eventPublisher, times(1)).publishEvent(any(NotificationCreatedEvent.class));
  }

  @Test
  void dispatch_bundleTargetFound_dedupes_noEvent() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.preference(user);
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.of(pref));
    Notification existing =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, NotificationStatus.UNREAD);
    existing.setBundleCount(1);
    when(notificationRepository.findOpenForBundling(any(), any(), any(), any()))
        .thenReturn(List.of(existing));
    when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Optional<UUID> result =
        dispatcher.dispatch(
            draft(
                user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, NotificationSeverity.ATTENTION));

    assertThat(result).contains(existing.getId());
    assertThat(existing.getBundleCount()).isEqualTo(2);
    ArgumentCaptor<DeliveryLog> logCaptor = ArgumentCaptor.forClass(DeliveryLog.class);
    verify(deliveryLogRepository).save(logCaptor.capture());
    assertThat(logCaptor.getValue().getSkipReason())
        .isEqualTo(DeliverySkipReason.DEDUPED_INTO_BUNDLE);
    verify(eventPublisher, never()).publishEvent(any(NotificationCreatedEvent.class));
    verify(notificationService, never()).create(any());
  }

  private static NotificationDto toDto(Notification n) {
    return new com.example.mealprep.notification.api.mapper.NotificationMapper() {}.toDto(n);
  }
}
