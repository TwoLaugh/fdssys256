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
 * <p>{@code destinationResult} is custom-mapped via {@code @Named("readDestinationResult")}, which
 * in v1 deliberately passes the raw {@link JsonNode} through verbatim (feedback-3). The originally
 * planned typed-shell decode — switching on the destination to deserialise into each destination's
 * {@code Result} record, with a {@code Map<String, Object>} fallback — was not built and is not
 * needed: the DTO field is typed {@code Object}, so Jackson serialises the JSONB straight back to
 * the client with no information loss. See lld/feedback.md §Mappers for the shipped-vs-designed
 * note.
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
   * Returns the raw {@link JsonNode} verbatim. v1 deliberately ships this pass-through rather than
   * a destination-keyed switch that materialises a typed shell (e.g. {@code AdaptationResult} for
   * RECIPE) — the {@code Object} DTO surface makes the raw node fully acceptable to the client
   * (feedback-3). Returning {@code null} for a {@code null} input keeps the DTO field absent on a
   * routing row that never recorded a destination result.
   */
  @Named("readDestinationResult")
  default Object readDestinationResult(JsonNode raw) {
    return raw;
  }
}
