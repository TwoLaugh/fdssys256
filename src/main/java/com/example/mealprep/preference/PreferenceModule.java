package com.example.mealprep.preference;

import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService;
import com.example.mealprep.preference.domain.service.PreferenceArchiveQueryService;
import com.example.mealprep.preference.domain.service.PreferenceArchiveUpdateService;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the preference module's public service interfaces. Cross-module
 * callers inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>Mirrors {@code AuthModule} and {@code CoreModule}; thin and carries no business logic. 01a
 * landed the hard-constraints query / update surfaces; 01b added {@link
 * HardConstraintFilterService}; 01c adds the taste-profile query + update pair; 01d adds the Tier-3
 * lifestyle-config surfaces; 01e adds the preference-archive query + update pair.
 */
@Component
public class PreferenceModule {

  private final PreferenceQueryService preferenceQueryService;
  private final PreferenceUpdateService preferenceUpdateService;
  private final HardConstraintFilterService hardConstraintFilterService;
  private final LifestyleConfigQueryService lifestyleConfigQueryService;
  private final LifestyleConfigUpdateService lifestyleConfigUpdateService;
  private final TasteProfileQueryService tasteProfileQueryService;
  private final TasteProfileUpdateService tasteProfileUpdateService;
  private final PreferenceArchiveQueryService preferenceArchiveQueryService;
  private final PreferenceArchiveUpdateService preferenceArchiveUpdateService;

  public PreferenceModule(
      PreferenceQueryService preferenceQueryService,
      PreferenceUpdateService preferenceUpdateService,
      HardConstraintFilterService hardConstraintFilterService,
      LifestyleConfigQueryService lifestyleConfigQueryService,
      LifestyleConfigUpdateService lifestyleConfigUpdateService,
      TasteProfileQueryService tasteProfileQueryService,
      TasteProfileUpdateService tasteProfileUpdateService,
      PreferenceArchiveQueryService preferenceArchiveQueryService,
      PreferenceArchiveUpdateService preferenceArchiveUpdateService) {
    this.preferenceQueryService = preferenceQueryService;
    this.preferenceUpdateService = preferenceUpdateService;
    this.hardConstraintFilterService = hardConstraintFilterService;
    this.lifestyleConfigQueryService = lifestyleConfigQueryService;
    this.lifestyleConfigUpdateService = lifestyleConfigUpdateService;
    this.tasteProfileQueryService = tasteProfileQueryService;
    this.tasteProfileUpdateService = tasteProfileUpdateService;
    this.preferenceArchiveQueryService = preferenceArchiveQueryService;
    this.preferenceArchiveUpdateService = preferenceArchiveUpdateService;
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

  public LifestyleConfigQueryService lifestyleConfigQuery() {
    return lifestyleConfigQueryService;
  }

  public LifestyleConfigUpdateService lifestyleConfigUpdate() {
    return lifestyleConfigUpdateService;
  }

  public TasteProfileQueryService tasteProfileQuery() {
    return tasteProfileQueryService;
  }

  public TasteProfileUpdateService tasteProfileUpdate() {
    return tasteProfileUpdateService;
  }

  public PreferenceArchiveQueryService preferenceArchiveQuery() {
    return preferenceArchiveQueryService;
  }

  public PreferenceArchiveUpdateService preferenceArchiveUpdate() {
    return preferenceArchiveUpdateService;
  }
}
