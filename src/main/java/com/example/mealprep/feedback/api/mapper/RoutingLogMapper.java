package com.example.mealprep.feedback.api.mapper;

import com.example.mealprep.feedback.api.dto.RoutingDecisionDto;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

/**
 * Entity ↔ DTO mapping for {@link RoutingLogEntry}.
 *
 * <p>{@code destinationResult} is custom-mapped via {@code @Named("readDestinationResult")}: a
 * no-op default in 01a that returns the raw {@link JsonNode}. The typed-shell logic (deserialising
 * into each destination's {@code Result} record) lands in feedback-01d alongside the destination
 * dispatchers.
 */
@Mapper(componentModel = "spring")
public interface RoutingLogMapper {

  default RoutingDecisionDto toDto(RoutingLogEntry entity) {
    if (entity == null) {
      return null;
    }
    return new RoutingDecisionDto(
        entity.getId(),
        entity.getDestination(),
        entity.getConfidence(),
        entity.getRoutingDecision(),
        entity.getStatus(),
        entity.getExtractedFeedback(),
        entity.getActionTaken(),
        readDestinationResult(entity.getDestinationResultJson()),
        entity.getFailureMessage());
  }

  default List<RoutingDecisionDto> toDtos(List<RoutingLogEntry> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<RoutingDecisionDto> out = new ArrayList<>(entities.size());
    for (RoutingLogEntry e : entities) {
      out.add(toDto(e));
    }
    return out;
  }

  /**
   * In 01a returns the raw {@link JsonNode} verbatim. Feedback-01d will replace this with a
   * destination-keyed switch that materialises the typed shell (e.g. {@code AdaptationResult} for
   * RECIPE).
   */
  @Named("readDestinationResult")
  default Object readDestinationResult(JsonNode raw) {
    return raw;
  }
}
