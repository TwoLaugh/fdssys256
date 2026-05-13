package com.example.mealprep.planner.domain.service;

import com.example.mealprep.planner.api.dto.PlanDto;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read surface of the planner module.
 *
 * <p>01a ships exactly one method — {@link #getPlanById(UUID)} — driving the single read endpoint
 * in this slice. The remaining methods listed in LLD §Service Interfaces (lines 564-579) land with
 * the tickets that own their controllers and behaviour:
 *
 * <ul>
 *   <li>{@code getActivePlan(householdId, weekStartDate)} → planner-01c
 *   <li>{@code getPlanHistory(householdId, weekStartDate)} → planner-01c
 *   <li>{@code getPlansBetween(householdId, from, to, pageable)} → planner-01c
 *   <li>{@code getPlansByIds(planIds)} → planner-01c (batch sibling for cross-module reads)
 *   <li>{@code getPendingSuggestions(householdId, pageable)} → planner-01c
 *   <li>{@code getSuggestion(suggestionId)} → planner-01c
 *   <li>{@code checkFeasibility(householdId, weekStartDate)} → planner-01c
 * </ul>
 *
 * Per the style guide, method declarations land with their implementations so we don't pollute the
 * surface with abstract-method placeholders that {@code UnsupportedOperationException}-on-call.
 */
public interface PlanQueryService {

  /**
   * Fetch a single plan by id, fully hydrated (days, slots, scheduled recipes). Days are ordered by
   * date; slots within each day are ordered by slot index. Returns {@link Optional#empty()} when
   * the plan does not exist.
   */
  Optional<PlanDto> getPlanById(UUID planId);
}
