package com.example.mealprep.planner.domain.repository;

import com.example.mealprep.planner.domain.entity.ReoptStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestion;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Package-private repository for {@link ReoptSuggestion}.
 *
 * <p>01a ships exactly the lookup needed by 01k's idempotency check — {@code (householdId,
 * weekStartDate, triggerEventId)} — so the listener doesn't have to amend this repository when it
 * lands. 01c adds the household-scoped {@link #findByHouseholdIdAndStatusOrderByCreatedAtDesc} read
 * used by the pending-suggestions endpoint. Backed by {@code idx_planner_reopt_pending}.
 */
public interface ReoptSuggestionRepository extends JpaRepository<ReoptSuggestion, UUID> {

  Optional<ReoptSuggestion> findByHouseholdIdAndWeekStartDateAndTriggerEventId(
      UUID householdId, LocalDate weekStartDate, UUID triggerEventId);

  Page<ReoptSuggestion> findByHouseholdIdAndStatusOrderByCreatedAtDesc(
      UUID householdId, ReoptStatus status, Pageable pageable);
}
