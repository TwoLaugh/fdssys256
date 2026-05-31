package com.example.mealprep.notification.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import com.example.mealprep.notification.domain.repository.NotificationRepository;
import com.example.mealprep.notification.testdata.NotificationTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationDebouncerTest {

  @Mock private NotificationRepository repository;
  private NotificationDebouncer debouncer;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    debouncer = new NotificationDebouncer(repository, objectMapper);
  }

  @Test
  void findBundleTarget_noOpenRow_returnsNull() {
    UUID user = UUID.randomUUID();
    when(repository.findOpenForBundling(any(), any(), any(), any())).thenReturn(List.of());

    Notification target =
        debouncer.findBundleTarget(
            user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, null, 30, Instant.now());

    assertThat(target).isNull();
  }

  @Test
  void findBundleTarget_openRowWithinWindow_returnsIt() {
    UUID user = UUID.randomUUID();
    Notification existing =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, NotificationStatus.UNREAD);
    when(repository.findOpenForBundling(any(), any(), any(), any())).thenReturn(List.of(existing));

    Notification target =
        debouncer.findBundleTarget(
            user, NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, null, 30, Instant.now());

    assertThat(target).isSameAs(existing);
  }

  @Test
  void findBundleTarget_perKeyMismatch_returnsNull() {
    UUID user = UUID.randomUUID();
    Notification existing =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, NotificationStatus.UNREAD);
    when(repository.findOpenForBundling(any(), any(), any(), any())).thenReturn(List.of(existing));

    Notification target =
        debouncer.findBundleTarget(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, "slot-123", 30, Instant.now());

    assertThat(target).isNull();
  }

  @Test
  void findBundleTarget_perKeyMatch_returnsRow() {
    UUID user = UUID.randomUUID();
    Notification existing =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, NotificationStatus.UNREAD);
    existing.setBundleKeys(debouncer.singletonKeyArray("slot-123"));
    when(repository.findOpenForBundling(any(), any(), any(), any())).thenReturn(List.of(existing));

    Notification target =
        debouncer.findBundleTarget(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, "slot-123", 30, Instant.now());

    assertThat(target).isSameAs(existing);
  }

  /**
   * notification-3: a newer different-key open row of the same {@code (user, kind)} must not hide
   * an older same-key row. The repository returns rows newest-first; a {@code LIMIT 1} lookup would
   * only ever see the newer different-key row (slot-999) and split the bundle. The debouncer must
   * scan the page and find the older same-key row (slot-123).
   */
  @Test
  void findBundleTarget_perKey_olderSameKeyHiddenBehindNewerDifferentKey_returnsOlderRow() {
    UUID user = UUID.randomUUID();
    Notification newerDifferentKey =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, NotificationStatus.UNREAD);
    newerDifferentKey.setBundleKeys(debouncer.singletonKeyArray("slot-999"));
    Notification olderSameKey =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, NotificationStatus.UNREAD);
    olderSameKey.setBundleKeys(debouncer.singletonKeyArray("slot-123"));
    // Repository contract: newest-first. The hidden same-key row is older, so it comes second.
    when(repository.findOpenForBundling(any(), any(), any(), any()))
        .thenReturn(List.of(newerDifferentKey, olderSameKey));

    Notification target =
        debouncer.findBundleTarget(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, "slot-123", 30, Instant.now());

    assertThat(target).isSameAs(olderSameKey);
  }

  /**
   * notification-3 corollary: when no open row carries the draft's key, even across a page of
   * different-key rows, the debouncer opens a fresh notification (returns null) rather than
   * mis-bundling onto an unrelated row.
   */
  @Test
  void findBundleTarget_perKey_pageHasNoMatchingKey_returnsNull() {
    UUID user = UUID.randomUUID();
    Notification rowA =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, NotificationStatus.UNREAD);
    rowA.setBundleKeys(debouncer.singletonKeyArray("slot-998"));
    Notification rowB =
        NotificationTestData.notification(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, NotificationStatus.UNREAD);
    rowB.setBundleKeys(debouncer.singletonKeyArray("slot-999"));
    when(repository.findOpenForBundling(any(), any(), any(), any()))
        .thenReturn(List.of(rowA, rowB));

    Notification target =
        debouncer.findBundleTarget(
            user, NotificationKind.PROVISION_DEFROST_REMINDER, "slot-123", 30, Instant.now());

    assertThat(target).isNull();
  }

  @Test
  void applyBundle_accumulates_incrementsCountAndAppendsKey() {
    Notification target =
        NotificationTestData.notification(
            UUID.randomUUID(),
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            NotificationStatus.UNREAD);
    target.setBundleCount(1);
    NotificationDraft draft =
        draft(NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, "key-1", NotificationSeverity.ATTENTION);

    debouncer.applyBundle(target, draft, Instant.now());

    assertThat(target.getBundleCount()).isEqualTo(2);
    assertThat(target.getBundleKeys()).isNotNull();
    assertThat(target.getBundleKeys().toString()).contains("key-1");
  }

  @Test
  void applyBundle_reoptReplacement_keepsCountAtOne_andReplacesBody() {
    Notification target =
        NotificationTestData.notification(
            UUID.randomUUID(), NotificationKind.PLANNER_REOPT_SUGGESTED, NotificationStatus.UNREAD);
    target.setBundleCount(1);
    NotificationDraft draft =
        draft(NotificationKind.PLANNER_REOPT_SUGGESTED, "plan-1", NotificationSeverity.ATTENTION);

    debouncer.applyBundle(target, draft, Instant.now());

    assertThat(target.getBundleCount()).isEqualTo(1);
    assertThat(target.getBody()).isEqualTo(draft.body());
  }

  private static NotificationDraft draft(
      NotificationKind kind, String bundlingKey, NotificationSeverity severity) {
    NotificationPayload payload =
        new NotificationPayload.ItemNearExpiryPayload(kind, List.of(), LocalDate.now(), 0);
    return TestDrafts.create(kind, bundlingKey, severity, payload);
  }
}
