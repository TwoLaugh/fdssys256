package com.example.mealprep.planner.domain.repository;

import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Plan}. {@code public} so the in-module {@code
 * domain.service.internal} package can inject it; cross-module isolation comes from {@code
 * PlannerBoundaryTest} (ArchUnit) — same pattern as {@code RecipeRepository}.
 *
 * <p>01a deliberately does NOT add an {@code @EntityGraph(attributePaths = {"days", "days.slots",
 * "days.slots.scheduledRecipe"})}: three chained {@code @OneToMany List<>} collections trigger
 * {@code MultipleBagFetchException} on Hibernate 6. The service touches lazy children inside a
 * {@code @Transactional(readOnly = true)} method instead — see {@code PlannerServiceImpl}.
 *
 * <p>01c adds the household-scoped query shapes (active/history/range/by-ids). None of them use
 * {@code @EntityGraph} for the same reason; the service hydrates per-plan inside the read txn.
 *
 * <p>01b also adds {@link #findFirstByHouseholdIdAndWeekStartDateAndStatus} (lifecycle reads use
 * the same lookup). Both tickets land the method additively; the signature is locked by the LLD so
 * the parallel-merge is a no-op.
 */
public interface PlanRepository extends JpaRepository<Plan, UUID> {

  /**
   * Used by internal test fixtures to assert a write landed for a given (household, week). Not
   * exposed via {@link com.example.mealprep.planner.domain.service.PlanQueryService} — that surface
   * adds household-scoped query methods in 01c.
   */
  Optional<Plan> findFirstByHouseholdIdAndWeekStartDate(UUID householdId, LocalDate weekStartDate);

  int countByHouseholdIdAndWeekStartDate(UUID householdId, LocalDate weekStartDate);

  /**
   * Returns the single {@link PlanStatus#ACTIVE} plan for a (household, week), if any. Backed by
   * {@code idx_planner_plans_household_week_status}. There is at most one ACTIVE row per
   * (household, week) — the lifecycle invariant is enforced in 01b's state machine. The {@code
   * findFirst} prefix is a safety belt; the index is unique only on (household, week, generation)
   * so we don't rely on the DB to enforce single-ACTIVE.
   *
   * <p>Also used by {@code PlanGenerationCounter.currentActivePlanIdFor} (01b) to populate {@code
   * Plan.replacesPlanId} during the generation flow.
   */
  Optional<Plan> findFirstByHouseholdIdAndWeekStartDateAndStatus(
      UUID householdId, LocalDate weekStartDate, PlanStatus status);

  /**
   * All plan generations for a (household, week), latest generation first. Backed by {@code
   * idx_planner_plans_household_week_gen}. The service caps the response at 100 via a {@link
   * Pageable} variant to avoid runaway responses.
   */
  Page<Plan> findByHouseholdIdAndWeekStartDateOrderByGenerationDesc(
      UUID householdId, LocalDate weekStartDate, Pageable pageable);

  /**
   * Range query — plans for a household whose {@code weekStartDate} falls in {@code [from, to]}
   * inclusive. Sort is locked at the repo level (the controller does NOT honour a caller-supplied
   * {@code ?sort=...} param — the LLD pins the order). Backed by {@code
   * idx_planner_plans_household_range}.
   */
  Page<Plan> findByHouseholdIdAndWeekStartDateBetweenOrderByWeekStartDateDescGenerationDesc(
      UUID householdId, LocalDate from, LocalDate to, Pageable pageable);

  /**
   * Bulk fetch by id. JPA spec does NOT preserve the input order — callers that care must reorder
   * client-side. Backed by the PK index.
   */
  List<Plan> findByIdIn(List<UUID> ids);
}
