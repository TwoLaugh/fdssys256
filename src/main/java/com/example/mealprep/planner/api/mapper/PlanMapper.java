package com.example.mealprep.planner.api.mapper;

import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.domain.entity.Plan;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Maps {@link Plan} → {@link PlanDto}. Days are delegated to {@link DayMapper}, which in turn
 * delegates per-slot mapping (and per-slot scheduled-recipe mapping) — so a single {@code
 * planMapper.toDto(plan)} call produces a fully-hydrated DTO graph. Child collection ordering is
 * applied by the child mappers ({@code days} by date, {@code slots} by slot index).
 */
@Mapper(componentModel = "spring")
public abstract class PlanMapper {

  @Autowired protected DayMapper dayMapper;

  public PlanDto toDto(Plan entity) {
    if (entity == null) {
      return null;
    }
    return new PlanDto(
        entity.getId(),
        entity.getHouseholdId(),
        entity.getWeekStartDate(),
        entity.getGeneration(),
        entity.getReplacesPlanId(),
        entity.getStatus(),
        entity.getTriggerKind(),
        entity.getTriggerEventId(),
        entity.isQualityWarning(),
        entity.isColdStart(),
        entity.isAiAugmented(),
        entity.getTraceId(),
        entity.getDecisionId(),
        entity.getAcceptedAt(),
        entity.getCompletedAt(),
        entity.getRejectedAt(),
        entity.getRejectedReason(),
        entity.getAbandonedAt(),
        entity.getAbandonedReason(),
        entity.getScoreBreakdown(),
        entity.getRollupSummary(),
        dayMapper.toDtos(entity.getDays()),
        entity.getVersion(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
