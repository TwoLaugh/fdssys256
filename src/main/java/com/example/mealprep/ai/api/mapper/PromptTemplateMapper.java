package com.example.mealprep.ai.api.mapper;

import com.example.mealprep.ai.api.dto.PromptTemplateDto;
import com.example.mealprep.ai.domain.entity.PromptTemplate;
import java.util.List;
import org.mapstruct.Mapper;

/** MapStruct mapper for {@link PromptTemplate} ↔ {@link PromptTemplateDto}. */
@Mapper
public interface PromptTemplateMapper {

  PromptTemplateDto toDto(PromptTemplate entity);

  List<PromptTemplateDto> toDtos(List<PromptTemplate> entities);
}
