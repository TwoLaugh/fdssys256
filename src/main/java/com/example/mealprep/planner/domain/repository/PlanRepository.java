package com.example.mealprep.planner.domain.repository;

import com.example.mealprep.planner.domain.entity.Plan;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
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
 */
public interface PlanRepository extends JpaRepository<Plan, UUID> {

  /**
   * Used by internal test fixtures to assert a write landed for a given (household, week). Not
   * exposed via {@link com.example.mealprep.planner.domain.service.PlanQueryService} — that surface
   * adds household-scoped query methods in 01c.
   */
  Optional<Plan> findFirstByHouseholdIdAndWeekStartDate(UUID householdId, LocalDate weekStartDate);

  int countByHouseholdIdAndWeekStartDate(UUID householdId, LocalDate weekStartDate);
}
