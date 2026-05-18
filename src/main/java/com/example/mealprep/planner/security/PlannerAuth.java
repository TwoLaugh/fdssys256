package com.example.mealprep.planner.security;

import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Plan ownership / household-membership authorisation helper (planner-01j).
 *
 * <p>The codebase does not enable Spring method-security ({@code @EnableMethodSecurity} is absent),
 * so the ticket's {@code @PreAuthorize("@plannerAuth.canAccessPlan(...)")} would be an inert
 * annotation. Instead — matching the established codebase idiom (see {@code
 * PendingChangesController}, which resolves the caller server-side and checks ownership in-method)
 * — the controller invokes this bean imperatively and maps a {@code false} verdict to 403.
 *
 * <p>The deny-by-default auth chain ({@code anyRequest().authenticated()}) already enforces 401 for
 * anonymous callers; this bean adds the cross-household 403.
 */
@Component("plannerAuth")
public class PlannerAuth {

  private final PlanRepository planRepository;
  private final HouseholdQueryService householdQueryService;

  public PlannerAuth(PlanRepository planRepository, HouseholdQueryService householdQueryService) {
    this.planRepository = planRepository;
    this.householdQueryService = householdQueryService;
  }

  /** True when {@code userId} is a member of {@code householdId}. */
  public boolean canAccessHousehold(UUID userId, UUID householdId) {
    if (userId == null || householdId == null) {
      return false;
    }
    return householdQueryService.getById(householdId).map(h -> h.members()).stream()
        .flatMap(java.util.List::stream)
        .map(HouseholdMemberDto::userId)
        .anyMatch(userId::equals);
  }

  /**
   * True when {@code userId} can access {@code planId} — i.e. the caller is a member of the plan's
   * owning household. A missing plan returns {@code false} (the controller maps the not-found case
   * separately so existence is not leaked through the auth path).
   */
  public boolean canAccessPlan(UUID userId, UUID planId) {
    if (userId == null || planId == null) {
      return false;
    }
    return planRepository
        .findById(planId)
        .map(Plan::getHouseholdId)
        .map(hid -> canAccessHousehold(userId, hid))
        .orElse(false);
  }
}
