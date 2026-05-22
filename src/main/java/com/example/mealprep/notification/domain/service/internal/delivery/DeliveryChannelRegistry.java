package com.example.mealprep.notification.domain.service.internal.delivery;

import com.example.mealprep.notification.domain.entity.Notification;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Auto-wires the full {@code List<DeliveryChannel>} of registered channel beans and resolves the
 * channels responsible for a given notification via {@link DeliveryChannel#accepts(Notification)}.
 * A future {@code PushDeliveryChannel} / {@code EmailDeliveryChannel} bean is picked up here
 * without any change to the dispatcher.
 */
@Component
public class DeliveryChannelRegistry {

  private final List<DeliveryChannel> channels;

  public DeliveryChannelRegistry(List<DeliveryChannel> channels) {
    this.channels = List.copyOf(channels);
  }

  /** The channels responsible for delivering this notification, in registration order. */
  public List<DeliveryChannel> channelsFor(Notification notification) {
    return channels.stream().filter(channel -> channel.accepts(notification)).toList();
  }
}
