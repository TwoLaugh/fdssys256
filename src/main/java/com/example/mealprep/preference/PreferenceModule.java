package com.example.mealprep.preference;

import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the preference module's public service interfaces. Cross-module
 * callers inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>Mirrors {@code AuthModule} and {@code CoreModule}; thin and carries no business logic. 01a
 * exposes only the partial query/update surfaces (hard constraints); taste profile, lifestyle
 * config, and {@code HardConstraintFilterService} land in subsequent tickets.
 */
@Component
public class PreferenceModule {

  private final PreferenceQueryService preferenceQueryService;
  private final PreferenceUpdateService preferenceUpdateService;

  public PreferenceModule(
      PreferenceQueryService preferenceQueryService,
      PreferenceUpdateService preferenceUpdateService) {
    this.preferenceQueryService = preferenceQueryService;
    this.preferenceUpdateService = preferenceUpdateService;
  }

  public PreferenceQueryService query() {
    return preferenceQueryService;
  }

  public PreferenceUpdateService update() {
    return preferenceUpdateService;
  }
}
