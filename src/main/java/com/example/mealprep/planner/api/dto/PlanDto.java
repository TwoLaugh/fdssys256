package com.example.mealprep.planner.api.dto;

import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The hydrated plan aggregate — locked public surface read by every wave-3 consumer (notification,
 * grocery, frontend). Days are ordered by {@code date} ascending; slots inside each day by {@code
 * slotIndex} ascending.
 */
public record PlanDto(
    UUID id,
    UUID householdId,
    LocalDate weekStartDate,
    int generation,
    UUID replacesPlanId,
    PlanStatus status,
    TriggerKind triggerKind,
    UUID triggerEventId,
    boolean qualityWarning,
    boolean coldStart,
    boolean aiAugmented,
    UUID traceId,
    UUID decisionId,
    Instant acceptedAt,
    Instant completedAt,
    Instant rejectedAt,
    String rejectedReason,
    Instant abandonedAt,
    String abandonedReason,
    ScoreBreakdownDocument scoreBreakdown,
    RollupSummaryDocument rollupSummary,
    List<DayDto> days,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
