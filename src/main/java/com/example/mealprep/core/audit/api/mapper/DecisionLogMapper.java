package com.example.mealprep.core.audit.api.mapper;

import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.domain.entity.DecisionLog;
import java.util.List;
import org.mapstruct.Mapper;

/** Entity ↔ DTO mapper for the decision log. */
@Mapper(componentModel = "spring")
public interface DecisionLogMapper {

  DecisionLogDto toDto(DecisionLog entity);

  List<DecisionLogDto> toDtos(List<DecisionLog> entities);
}
