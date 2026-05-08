package com.example.mealprep.ai.api.mapper;

import com.example.mealprep.ai.api.dto.AiCallLogDto;
import com.example.mealprep.ai.domain.entity.AiCallLog;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link AiCallLog} ↔ {@link AiCallLogDto}. Component-model is configured via
 * {@code -Amapstruct.defaultComponentModel=spring} on the compiler plugin.
 */
@Mapper
public interface AiCallLogMapper {

  AiCallLogDto toDto(AiCallLog entity);

  List<AiCallLogDto> toDtos(List<AiCallLog> entities);
}
