package com.example.mealprep.notification.domain.service.internal.delivery;

import com.example.mealprep.notification.domain.entity.DeliveryOutcome;
import com.example.mealprep.notification.domain.entity.Notification;

/**
 * Delivery-channel SPI. The dispatcher resolves the channels responsible for a persisted {@link
 * Notification} via {@link #accepts(Notification)} and fans the notification out to each, recording
 * one {@code notification_delivery_log} row per channel.
 *
 * <p>This interface is intentionally <strong>public</strong> (the only public type under {@code
 * internal/}) so a future {@code PushDeliveryChannel} / {@code EmailDeliveryChannel} — potentially
 * shipped from a sibling module — can plug in as a Spring bean. In v1 the sole implementation is
 * {@link InAppDeliveryChannel}.
 */
public interface DeliveryChannel {

  /**
   * Transport a notification can be delivered over. {@code PUSH}/{@code EMAIL} are v1 placeholders.
   */
  enum Channel {
    IN_APP,
    PUSH,
    EMAIL
  }

  /** The channel this implementation delivers over. */
  Channel channel();

  /**
   * Returns {@code true} if this channel is responsible for the given notification. Lets routing be
   * driven from kind/severity rather than a static config map.
   */
  boolean accepts(Notification notification);

  /** Deliver the notification over this channel, returning the attempt outcome. */
  DeliveryOutcome deliver(Notification notification);
}
