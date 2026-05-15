package com.example.mealprep.planner.domain.service.internal.lifecycle;

import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helper that computes the next plan-generation counter for a {@code (householdId, weekStartDate)}
 * scope and exposes the current {@code ACTIVE} plan id for that scope. Both reads are pure (no
 * writes) and share the existing {@code idx_planner_plans_household_week_status} index from 01a.
 *
 * <p>Package-private — only consumers inside {@code domain.service.internal} (the generation flow
 * in 01j, the revert flow in 01j) call it.
 */
@Component
@RequiredArgsConstructor
class PlanGenerationCounter {

  private final PlanRepository planRepository;

  /**
   * Next generation number for the scope. {@code 1} when no plan exists yet; otherwise {@code 1 +
   * count}. The generation flow assigns this to {@code Plan.generation} before persistence.
   */
  @Transactional(readOnly = true)
  public int nextGenerationFor(UUID householdId, LocalDate weekStartDate) {
    return 1 + planRepository.countByHouseholdIdAndWeekStartDate(householdId, weekStartDate);
  }

  /**
   * Id of the {@code ACTIVE} plan for the scope, if one exists. Used by the generation flow to
   * populate {@code Plan.replacesPlanId}; returns {@link Optional#empty()} when no {@code ACTIVE}
   * plan exists (only {@code GENERATED} or only terminal plans).
   */
  @Transactional(readOnly = true)
  public Optional<UUID> currentActivePlanIdFor(UUID householdId, LocalDate weekStartDate) {
    return planRepository
        .findFirstByHouseholdIdAndWeekStartDateAndStatus(
            householdId, weekStartDate, PlanStatus.ACTIVE)
        .map(Plan::getId);
  }
}
