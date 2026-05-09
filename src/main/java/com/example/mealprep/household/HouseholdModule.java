package com.example.mealprep.household;

import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the household module's public service interfaces. Cross-module callers
 * inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>Mirrors {@code AuthModule} / {@code PreferenceModule} / {@code CoreModule}; thin and carries
 * no business logic.
 */
@Component
public class HouseholdModule {

  private final HouseholdQueryService householdQueryService;
  private final HouseholdUpdateService householdUpdateService;

  public HouseholdModule(
      HouseholdQueryService householdQueryService, HouseholdUpdateService householdUpdateService) {
    this.householdQueryService = householdQueryService;
    this.householdUpdateService = householdUpdateService;
  }

  public HouseholdQueryService query() {
    return householdQueryService;
  }

  public HouseholdUpdateService update() {
    return householdUpdateService;
  }
}
