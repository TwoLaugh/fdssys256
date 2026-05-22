package com.example.mealprep.notification.api.mapper;

import com.example.mealprep.notification.api.dto.NotificationDto;
import com.example.mealprep.notification.domain.entity.Notification;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * {@link Notification} → {@link NotificationDto} mapping. The sealed {@code payload} passes through
 * unchanged (the JSONB type handler does persistence-side conversion); {@code bundleKeys} is a
 * JSONB array projected to a {@code List<String>} for the API surface.
 */
@Mapper(componentModel = "spring")
public interface NotificationMapper {

  default NotificationDto toDto(Notification entity) {
    if (entity == null) {
      return null;
    }
    return new NotificationDto(
        entity.getId(),
        entity.getUserId(),
        entity.getHouseholdId(),
        entity.getKind(),
        entity.getSeverity(),
        entity.getTitle(),
        entity.getBody(),
        entity.getPayload(),
        entity.getStatus(),
        entity.getActionTargetUri(),
        entity.getBundleCount(),
        toStringList(entity.getBundleKeys()),
        entity.getTraceId(),
        entity.getCreatedAt(),
        entity.getReadAt(),
        entity.getActionedAt(),
        entity.getDismissedAt(),
        entity.getOptimisticVersion());
  }

  private static List<String> toStringList(JsonNode node) {
    if (node == null || node.isNull() || !node.isArray()) {
      return Collections.emptyList();
    }
    List<String> result = new ArrayList<>(node.size());
    node.forEach(element -> result.add(element.asText()));
    return result;
  }
}
