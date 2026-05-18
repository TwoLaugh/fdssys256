package com.example.mealprep.planner.domain.repository;

import com.example.mealprep.planner.domain.entity.MealSlot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Package-private repository for {@link MealSlot}. Empty in 01a; planner-01j (slot-state
 * controller) adds {@code findByIdAndPlanId} and planner-01i (re-opt) adds {@code
 * findAllByPlanIdAndStateIn} / {@code findAllByPlanIdOrderByDayOnDateAscSlotIndexAsc}.
 */
public interface MealSlotRepository extends JpaRepository<MealSlot, UUID> {

  /**
   * Slot-state controller lookup (planner-01j) — fetch a slot scoped to its parent plan so a caller
   * cannot mutate a slot by id alone without naming the plan it belongs to.
   */
  Optional<MealSlot> findByIdAndPlanId(UUID id, UUID planId);
}
