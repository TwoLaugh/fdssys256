package com.example.mealprep.household.domain.repository;

import com.example.mealprep.household.domain.entity.Household;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Household}. Cross-module callers go through {@code
 * HouseholdQueryService} / {@code HouseholdUpdateService} — enforced by {@code
 * HouseholdBoundaryTest} (ArchUnit), which is the architectural boundary mechanism. The interface
 * is {@code public} only because the in-module {@code domain.service.internal} package needs to
 * inject it; package-private would prevent any reference from another package, including same-
 * module ones. The boundary test, not Java visibility, fences cross-module reach-through.
 */
public interface HouseholdRepository extends JpaRepository<Household, UUID> {

  @EntityGraph(attributePaths = {"members"})
  Optional<Household> findWithMembersById(UUID id);
}
