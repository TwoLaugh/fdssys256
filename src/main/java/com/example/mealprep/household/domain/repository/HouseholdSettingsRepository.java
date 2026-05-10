package com.example.mealprep.household.domain.repository;

import com.example.mealprep.household.domain.entity.HouseholdSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link HouseholdSettings}. Cross-module callers go through {@code
 * HouseholdQueryService} / {@code HouseholdUpdateService} — enforced by {@code
 * HouseholdBoundaryTest} (ArchUnit). The interface is {@code public} only because the in-module
 * {@code domain.service.internal} package needs to inject it; the boundary test, not Java
 * visibility, fences cross-module reach-through.
 */
public interface HouseholdSettingsRepository extends JpaRepository<HouseholdSettings, UUID> {

  Optional<HouseholdSettings> findByHouseholdId(UUID householdId);
}
