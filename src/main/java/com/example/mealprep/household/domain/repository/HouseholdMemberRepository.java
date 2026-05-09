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
}
