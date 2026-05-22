package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.entity.DeliveryOutcome;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import com.example.mealprep.notification.domain.service.internal.delivery.DeliveryChannel;
import com.example.mealprep.notification.domain.service.internal.delivery.InAppDeliveryChannel;
import com.example.mealprep.notification.testdata.NotificationTestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InAppDeliveryChannelTest {

  private final InAppDeliveryChannel channel = new InAppDeliveryChannel();

  @Test
  void channel_isInApp() {
    assertThat(channel.channel()).isEqualTo(DeliveryChannel.Channel.IN_APP);
  }

  @Test
  void accepts_everyKind() {
    for (NotificationKind kind : NotificationKind.values()) {
      var notification =
          NotificationTestData.notification(UUID.randomUUID(), kind, NotificationStatus.UNREAD);
      assertThat(channel.accepts(notification)).as("accepts %s", kind).isTrue();
    }
  }

  @Test
  void deliver_returnsDelivered() {
    var notification =
        NotificationTestData.notification(
            UUID.randomUUID(),
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            NotificationStatus.UNREAD);
    assertThat(channel.deliver(notification)).isEqualTo(DeliveryOutcome.DELIVERED);
  }
}
