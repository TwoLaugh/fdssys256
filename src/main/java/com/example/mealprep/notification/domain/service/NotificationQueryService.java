package com.example.mealprep.notification.domain.service;

import com.example.mealprep.notification.api.dto.DeliveryLogEntryDto;
import com.example.mealprep.notification.api.dto.NotificationDto;
import com.example.mealprep.notification.api.dto.NotificationListFilter;
import com.example.mealprep.notification.api.dto.NotificationPreferenceDto;
import com.example.mealprep.notification.api.dto.NotificationSummaryDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public read API for the notification module. Every read is scoped to {@code userId} — user A
 * cannot read user B's notifications. Cross-module callers inject this via {@code
 * NotificationModule}.
 */
public interface NotificationQueryService {

  Optional<NotificationDto> getById(UUID userId, UUID notificationId);

  List<NotificationDto> getByIds(UUID userId, List<UUID> notificationIds);

  Page<NotificationDto> list(UUID userId, NotificationListFilter filter, Pageable pageable);

  NotificationSummaryDto getSummary(UUID userId);

  NotificationPreferenceDto getPreferences(UUID userId);

  Page<DeliveryLogEntryDto> getDeliveryLog(UUID userId, UUID notificationId, Pageable pageable);
}
