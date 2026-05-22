package com.example.mealprep.notification.api.dto;

import com.example.mealprep.notification.domain.entity.DeliveryOutcome;
import com.example.mealprep.notification.domain.entity.DeliverySkipReason;
import com.example.mealprep.notification.domain.service.internal.delivery.DeliveryChannel;
import java.time.Instant;
import java.util.UUID;

/** Read shape of a single delivery-log entry for a notification. */
public record DeliveryLogEntryDto(
    UUID id,
    UUID notificationId,
    DeliveryChannel.Channel channel,
    DeliveryOutcome outcome,
    DeliverySkipReason skipReason,
    Instant attemptedAt) {}
