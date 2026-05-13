package com.example.mealprep.feedback.api.mapper;

import com.example.mealprep.feedback.api.dto.ClarificationOptionDto;
import com.example.mealprep.feedback.api.dto.ClarificationQueryDto;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Entity ↔ DTO mapping for {@link ClarificationQuery}. {@code options} is deserialised from {@code
 * classifierOptionsJson} via a shared {@link ObjectMapper} — Spring autowires it onto the
 * field-injected protected member after MapStruct generates the no-arg subclass.
 */
@Mapper(componentModel = "spring")
public abstract class ClarificationQueryMapper {

  private static final TypeReference<List<ClarificationOptionDto>> OPTIONS_TYPE =
      new TypeReference<>() {};

  @Autowired protected ObjectMapper objectMapper;

  public ClarificationQueryDto toDto(ClarificationQuery entity) {
    if (entity == null) {
      return null;
    }
    return new ClarificationQueryDto(
        entity.getId(),
        entity.getFeedbackEntry() != null ? entity.getFeedbackEntry().getId() : null,
        entity.getQuestionText(),
        readOptions(entity.getClassifierOptionsJson()),
        entity.getStatus(),
        entity.getExpiresAt(),
        entity.getCreatedAt());
  }

  public List<ClarificationQueryDto> toDtos(List<ClarificationQuery> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<ClarificationQueryDto> out = new ArrayList<>(entities.size());
    for (ClarificationQuery e : entities) {
      out.add(toDto(e));
    }
    return out;
  }

  /** Visible for tests; deserialises the JSON array into typed records. */
  List<ClarificationOptionDto> readOptions(JsonNode raw) {
    if (raw == null || raw.isNull() || raw.isMissingNode()) {
      return Collections.emptyList();
    }
    try {
      List<ClarificationOptionDto> parsed = objectMapper.convertValue(raw, OPTIONS_TYPE);
      return parsed != null ? parsed : Collections.emptyList();
    } catch (IllegalArgumentException ex) {
      // Defensive: malformed JSON in the column shouldn't fail the read. Return empty + let
      // the caller surface "no options"; the query stays answerable by free-text.
      return Collections.emptyList();
    }
  }
}
