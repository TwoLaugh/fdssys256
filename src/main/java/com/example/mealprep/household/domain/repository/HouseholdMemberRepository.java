package com.example.mealprep.household.domain.repository;

import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link HouseholdMember}. Cross-module callers go through {@code
 * HouseholdQueryService} / {@code HouseholdUpdateService} — enforced by {@code
 * HouseholdBoundaryTest} (ArchUnit). See {@link HouseholdRepository} for visibility note.
 */
public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, UUID> {

  Optional<HouseholdMember> findByUserId(UUID userId);

  boolean existsByHouseholdIdAndRole(UUID householdId, HouseholdRole role);

  /** Total members in a household — used by 01d's only-member case in the last-primary guard. */
  long countByHouseholdId(UUID householdId);

  /**
   * Member count by role within a household. 01d's last-primary guard uses this to detect the
   * "demoting / removing the only primary while other members remain" case.
   */
  long countByHouseholdIdAndRole(UUID householdId, HouseholdRole role);
}
