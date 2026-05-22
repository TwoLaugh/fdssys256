package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.notification.api.dto.UpdateNotificationPreferenceRequest;
import com.example.mealprep.notification.api.mapper.DeliveryLogMapper;
import com.example.mealprep.notification.api.mapper.NotificationMapper;
import com.example.mealprep.notification.api.mapper.NotificationPreferenceMapper;
import com.example.mealprep.notification.config.NotificationProperties;
import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPreference;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import com.example.mealprep.notification.domain.repository.DeliveryLogRepository;
import com.example.mealprep.notification.domain.repository.NotificationPreferenceRepository;
import com.example.mealprep.notification.domain.repository.NotificationRepository;
import com.example.mealprep.notification.domain.service.internal.NotificationServiceImpl;
import com.example.mealprep.notification.exception.NotificationNotFoundException;
import com.example.mealprep.notification.exception.NotificationStateTransitionException;
import com.example.mealprep.notification.testdata.NotificationTestData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationPreferenceRepository preferenceRepository;
  @Mock private DeliveryLogRepository deliveryLogRepository;

  private NotificationServiceImpl service;
  private final NotificationMapper notificationMapper = new NotificationMapper() {};
  private final NotificationPreferenceMapper preferenceMapper =
      new NotificationPreferenceMapper() {};
  private final DeliveryLogMapper deliveryLogMapper = new DeliveryLogMapper() {};
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    service =
        new NotificationServiceImpl(
            notificationRepository,
            preferenceRepository,
            notificationMapper,
            preferenceMapper,
            deliveryLogMapper,
            deliveryLogRepository,
            new NotificationProperties(null, null, null),
            clock);
  }

  @Test
  void getById_enforcesUserScoping() {
    UUID user = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(notificationRepository.findByIdAndUserId(id, user)).thenReturn(Optional.empty());

    assertThat(service.getById(user, id)).isEmpty();
    verify(notificationRepository).findByIdAndUserId(id, user);
  }

  @Test
  void markRead_unreadToRead_setsReadAt() {
    UUID user = UUID.randomUUID();
    Notification n =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_ITEM_SPOILED, NotificationStatus.UNREAD);
    when(notificationRepository.findByIdAndUserId(n.getId(), user)).thenReturn(Optional.of(n));
    when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var dto = service.markRead(user, n.getId());

    assertThat(dto.status()).isEqualTo(NotificationStatus.READ);
    assertThat(dto.readAt()).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
  }

  @Test
  void markRead_fromDismissed_throwsStateTransition() {
    UUID user = UUID.randomUUID();
    Notification n =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_ITEM_SPOILED, NotificationStatus.DISMISSED);
    when(notificationRepository.findByIdAndUserId(n.getId(), user)).thenReturn(Optional.of(n));

    assertThatThrownBy(() -> service.markRead(user, n.getId()))
        .isInstanceOf(NotificationStateTransitionException.class);
    verify(notificationRepository, never()).save(any());
  }

  @Test
  void markActioned_fromRead_isLegal() {
    UUID user = UUID.randomUUID();
    Notification n =
        NotificationTestData.notification(
            user, NotificationKind.PLANNER_REOPT_SUGGESTED, NotificationStatus.READ);
    when(notificationRepository.findByIdAndUserId(n.getId(), user)).thenReturn(Optional.of(n));
    when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var dto = service.markActioned(user, n.getId());

    assertThat(dto.status()).isEqualTo(NotificationStatus.ACTIONED);
  }

  @Test
  void markRead_notFound_throws() {
    UUID user = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(notificationRepository.findByIdAndUserId(id, user)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.markRead(user, id))
        .isInstanceOf(NotificationNotFoundException.class);
  }

  @Test
  void markAllRead_emptyKinds_marksAll() {
    UUID user = UUID.randomUUID();
    when(notificationRepository.markAllReadForUser(eq(user), any())).thenReturn(5);

    int updated = service.markAllRead(user, Set.of());

    assertThat(updated).isEqualTo(5);
    verify(notificationRepository).markAllReadForUser(eq(user), any());
    verify(notificationRepository, never()).markReadForUserAndKinds(any(), any(), any());
  }

  @Test
  void markAllRead_withKinds_marksOnlyThoseKinds() {
    UUID user = UUID.randomUUID();
    Set<NotificationKind> kinds = Set.of(NotificationKind.PROVISION_ITEM_NEAR_EXPIRY);
    when(notificationRepository.markReadForUserAndKinds(eq(user), eq(kinds), any())).thenReturn(2);

    int updated = service.markAllRead(user, kinds);

    assertThat(updated).isEqualTo(2);
    verify(notificationRepository, never()).markAllReadForUser(any(), any());
  }

  @Test
  void ensurePreferencesForUser_existing_isNoOp() {
    UUID user = UUID.randomUUID();
    NotificationPreference existing = NotificationTestData.preference(user);
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.of(existing));

    var dto = service.ensurePreferencesForUser(user);

    assertThat(dto.userId()).isEqualTo(user);
    verify(preferenceRepository, never()).save(any());
  }

  @Test
  void ensurePreferencesForUser_missing_createsDefaults() {
    UUID user = UUID.randomUUID();
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.empty());
    when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var dto = service.ensurePreferencesForUser(user);

    assertThat(dto.enabledKinds()).containsKey(NotificationKind.PLANNER_PLAN_GENERATED);
    // Default OFF for plan-generated.
    assertThat(dto.enabledKinds().get(NotificationKind.PLANNER_PLAN_GENERATED)).isFalse();
    verify(preferenceRepository).save(any());
  }

  @Test
  void updatePreferences_staleVersion_throwsOptimisticLock() {
    UUID user = UUID.randomUUID();
    NotificationPreference existing = NotificationTestData.preference(user);
    existing.setOptimisticVersion(3L);
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.of(existing));

    var request = updateRequest(0L);

    assertThatThrownBy(() -> service.updatePreferences(user, request))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void updatePreferences_partialKindMap_rejected() {
    UUID user = UUID.randomUUID();
    NotificationPreference existing = NotificationTestData.preference(user);
    when(preferenceRepository.findByUserId(user)).thenReturn(Optional.of(existing));
    var request =
        new UpdateNotificationPreferenceRequest(
            Map.of(NotificationKind.PROVISION_ITEM_SPOILED, true),
            false,
            null,
            null,
            "Europe/London",
            30,
            0L);

    assertThatThrownBy(() -> service.updatePreferences(user, request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static UpdateNotificationPreferenceRequest updateRequest(long expectedVersion) {
    return new UpdateNotificationPreferenceRequest(
        NotificationTestData.allEnabled(), false, null, null, "Europe/London", 30, expectedVersion);
  }
}
