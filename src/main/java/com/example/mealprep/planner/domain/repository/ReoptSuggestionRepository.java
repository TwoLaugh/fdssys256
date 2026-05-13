package com.example.mealprep.planner.domain.repository;

import com.example.mealprep.planner.domain.entity.ReoptSuggestion;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Package-private repository for {@link ReoptSuggestion}.
 *
 * <p>01a ships exactly the lookup needed by 01k's idempotency check — {@code (householdId,
 * weekStartDate, triggerEventId)} — so the listener doesn't have to amend this repository when it
 * lands. Other read shapes (paged pending list, expired sweep) defer to planner-01c and
 * planner-01k.
 */
public interface ReoptSuggestionRepository extends JpaRepository<ReoptSuggestion, UUID> {

  Optional<ReoptSuggestion> findByHouseholdIdAndWeekStartDateAndTriggerEventId(
      UUID householdId, LocalDate weekStartDate, UUID triggerEventId);
}
