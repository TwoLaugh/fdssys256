package com.example.mealprep.notification.api.dto;

import java.time.Instant;

/** Inbox badge summary: counts of unread / attention / urgent notifications. */
public record NotificationSummaryDto(
    int unreadCount, int attentionCount, int urgentCount, Instant generatedAt) {}
