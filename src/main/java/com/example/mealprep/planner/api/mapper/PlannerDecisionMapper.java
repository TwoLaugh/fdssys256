package com.example.mealprep.planner.api.mapper;

import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.planner.api.dto.PlannerDecisionRowDto;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * Maps the cross-cutting {@code core.audit.DecisionLogDto} onto the planner-facing {@link
 * PlannerDecisionRowDto} (planner-01l admin endpoint). The {@code kind} is lifted out of the {@code
 * inputs} JSON — the shared {@code decision_log} table has no dedicated kind column, so {@link
 * com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter} stamps it
 * into {@code inputs.kind}.
 */
@Mapper(componentModel = "spring")
public interface PlannerDecisionMapper {

  @Mapping(target = "outputs", source = "chosen")
  @Mapping(target = "kind", source = "inputs", qualifiedByName = "kindFromInputs")
  PlannerDecisionRowDto toRow(DecisionLogDto dto);

  List<PlannerDecisionRowDto> toRows(List<DecisionLogDto> dtos);

  /** Pull {@code inputs.kind} (written by {@code DecisionLogWriter}); null if absent/blank. */
  @Named("kindFromInputs")
  default String kindFromInputs(JsonNode inputs) {
    if (inputs == null) {
      return null;
    }
    JsonNode kind = inputs.get("kind");
    return kind == null || kind.isNull() ? null : kind.asText();
  }
}
