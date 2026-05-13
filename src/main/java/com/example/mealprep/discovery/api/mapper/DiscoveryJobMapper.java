package com.example.mealprep.discovery.api.mapper;

import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Entity ↔ DTO mapping for {@link DiscoveryJob}. The {@code constraintsJson} {@code JsonNode}
 * column is converted to {@link DiscoveryConstraints} via Jackson — handwritten because the
 * JsonNode → record bridge needs an {@code ObjectMapper} that MapStruct's default name-matching
 * can't supply.
 */
@Component
public class DiscoveryJobMapper {

  private final ObjectMapper objectMapper;

  @Autowired
  public DiscoveryJobMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public DiscoveryJobDto toDto(DiscoveryJob entity) {
    if (entity == null) {
      return null;
    }
    return new DiscoveryJobDto(
        entity.getId(),
        entity.getUserId(),
        entity.getTrigger(),
        entity.getRequestedCount(),
        toConstraints(entity.getConstraintsJson()),
        copyOrEmpty(entity.getSourcesRequested()),
        entity.getStatus(),
        entity.getQueuedAt(),
        entity.getStartedAt(),
        entity.getCompletedAt(),
        entity.getCandidatesSeen(),
        entity.getCandidatesAfterFilter(),
        entity.getRecipesIngested(),
        entity.getRecipesSkippedDuplicate(),
        copyOrEmpty(entity.getSourcesSucceeded()),
        copyOrEmpty(entity.getSourcesFailed()),
        entity.getErrorSummary(),
        entity.getTraceId(),
        entity.getOptimisticVersion());
  }

  public List<DiscoveryJobDto> toDtos(List<DiscoveryJob> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<DiscoveryJobDto> out = new ArrayList<>(entities.size());
    for (DiscoveryJob e : entities) {
      out.add(toDto(e));
    }
    return out;
  }

  /**
   * Convert the persisted opaque {@link JsonNode} into a typed {@link DiscoveryConstraints}. Returns
   * {@code null} for a null node so the field surfaces as {@code null} on the wire when the column
   * (theoretically) is absent — 01b's service code always writes it, so this is defensive.
   */
  private DiscoveryConstraints toConstraints(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    try {
      return objectMapper.treeToValue(node, DiscoveryConstraints.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Failed to deserialise discovery_jobs.constraints_json into DiscoveryConstraints", e);
    }
  }

  private static List<String> copyOrEmpty(List<String> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    return new ArrayList<>(source);
  }
}
