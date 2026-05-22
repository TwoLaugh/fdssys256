package com.example.mealprep.notification.api.mapper;

import com.example.mealprep.notification.api.dto.DeliveryLogEntryDto;
import com.example.mealprep.notification.domain.entity.DeliveryLog;
import org.mapstruct.Mapper;

/** {@link DeliveryLog} → {@link DeliveryLogEntryDto} mapping. */
@Mapper(componentModel = "spring")
public interface DeliveryLogMapper {

  default DeliveryLogEntryDto toDto(DeliveryLog entity) {
    if (entity == null) {
      return null;
    }
    return new DeliveryLogEntryDto(
        entity.getId(),
        entity.getNotification() == null ? null : entity.getNotification().getId(),
        entity.getChannel(),
        entity.getOutcome(),
        entity.getSkipReason(),
        entity.getAttemptedAt());
  }
}
