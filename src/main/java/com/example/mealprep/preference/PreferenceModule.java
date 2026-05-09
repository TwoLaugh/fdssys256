package com.example.mealprep.preference;

import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the preference module's public service interfaces. Cross-module
 * callers inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>Mirrors {@code AuthModule} and {@code CoreModule}; thin and carries no business logic. 01a
 * landed the hard-constraints query / update surfaces; 01b adds {@link
 * HardConstraintFilterService}, the hot-path read every food-output module calls.
 */
@Component
public class PreferenceModule {

  private final PreferenceQueryService preferenceQueryService;
  private final PreferenceUpdateService preferenceUpdateService;
  private final HardConstraintFilterService hardConstraintFilterService;

  public PreferenceModule(
      PreferenceQueryService preferenceQueryService,
      PreferenceUpdateService preferenceUpdateService,
      HardConstraintFilterService hardConstraintFilterService) {
    this.preferenceQueryService = preferenceQueryService;
    this.preferenceUpdateService = preferenceUpdateService;
    this.hardConstraintFilterService = hardConstraintFilterService;
  }

  public PreferenceQueryService query() {
    return preferenceQueryService;
  }

  public PreferenceUpdateService update() {
    return preferenceUpdateService;
  }

  public HardConstraintFilterService filter() {
    return hardConstraintFilterService;
  }
}
