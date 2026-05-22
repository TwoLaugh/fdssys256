package com.example.mealprep.notification.api.dto;

import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import java.time.Instant;
import java.util.Set;

/**
 * Filter for the inbox list endpoint. Any field may be null/empty (no constraint on that
 * dimension). {@code statuses} empty → all statuses; {@code kinds} empty → all kinds; {@code since}
 * null → no lower bound.
 */
public record NotificationListFilter(
    Set<NotificationStatus> statuses, Set<NotificationKind> kinds, Instant since) {}
