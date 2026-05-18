package com.example.mealprep.planner.api.mapper;

import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.ReoptSuggestionDto;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.ReoptSuggestion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Maps {@link ReoptSuggestion} → {@link ReoptSuggestionDto}. Standalone aggregate — no nested
 * mappers needed.
 */
@Mapper(componentModel = "spring")
public abstract class ReoptSuggestionMapper {

  public ReoptSuggestionDto toDto(ReoptSuggestion entity) {
    if (entity == null) {
      return null;
    }
    return new ReoptSuggestionDto(
        entity.getId(),
        entity.getHouseholdId(),
        entity.getWeekStartDate(),
        entity.getPlanId(),
        entity.getTriggerKind(),
        entity.getTriggerEventId(),
        entity.getAffectedSlotIds() == null
            ? Collections.emptyList()
            : List.copyOf(entity.getAffectedSlotIds()),
        entity.getSummary(),
        entity.getStatus(),
        entity.getExpiresAt(),
        entity.getCreatedAt(),
        entity.getResolvedAt());
  }

  /**
   * Maps the planner-01i {@link MealPrepPlanReoptSuggestion} aggregate (the materialised mid-week
   * re-opt proposal) to its read shape. Used by planner-01j's accept/reject endpoints.
   */
  public PlanReoptSuggestionDto toPlanReoptDto(MealPrepPlanReoptSuggestion entity) {
    if (entity == null) {
      return null;
    }
    return new PlanReoptSuggestionDto(
        entity.getId(),
        entity.getPlanId(),
        entity.getTriggerKind(),
        entity.getTriggerEventId(),
        entity.getTraceId(),
        entity.getDecisionId(),
        entity.getSummary(),
        entity.getStatus(),
        entity.getProposedAssignments(),
        entity.getCreatedAt(),
        entity.getExpiresAt());
  }

  public List<ReoptSuggestionDto> toDtos(List<ReoptSuggestion> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<ReoptSuggestionDto> out = new ArrayList<>(entities.size());
    for (ReoptSuggestion entity : entities) {
      out.add(toDto(entity));
    }
    return out;
  }
}
