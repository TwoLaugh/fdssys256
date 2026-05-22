package com.example.mealprep.notification.domain.service.internal.delivery;

import com.example.mealprep.notification.domain.entity.DeliveryOutcome;
import com.example.mealprep.notification.domain.entity.Notification;
import org.springframework.stereotype.Component;

/**
 * The sole v1 delivery channel. The in-app feed is a database query against {@code notifications};
 * "delivery" is just the marker that the row was committed, so there is no network I/O and no
 * meaningful failure mode. {@link #accepts(Notification)} returns {@code true} for every
 * notification. The delivery-log row itself is written by the dispatcher after a successful {@link
 * #deliver(Notification)}.
 */
@Component
public class InAppDeliveryChannel implements DeliveryChannel {

  @Override
  public Channel channel() {
    return Channel.IN_APP;
  }

  @Override
  public boolean accepts(Notification notification) {
    return true;
  }

  @Override
  public DeliveryOutcome deliver(Notification notification) {
    return DeliveryOutcome.DELIVERED;
  }
}
