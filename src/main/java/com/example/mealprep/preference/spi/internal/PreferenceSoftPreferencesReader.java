package com.example.mealprep.preference.spi.internal;

import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.household.spi.SoftPreferencesReader;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Real {@link SoftPreferencesReader} for the household soft-preference merge — the preference side
 * of household/preference-01g. As a plain {@code @Component} it out-ranks the household module's
 * {@code NoopSoftPreferencesReader} {@code @Bean @ConditionalOnMissingBean}, so with preference on
 * the classpath {@code HouseholdServiceImpl}'s {@code ObjectProvider.getObject()} resolves this
 * bean. (The Noop stays in place for household-only test slices that don't load preference → empty
 * bundles.)
 *
 * <p>Thin adapter: delegates to {@link PreferenceQueryService#getSoftPreferencesByUserIds(List)},
 * which projects each user's taste profile + lifestyle config down to the NON-VECTOR household
 * bundle shape. The taste vector / embedding is never read on this path (see {@code
 * SoftPreferenceProjection}).
 */
@Component
public class PreferenceSoftPreferencesReader implements SoftPreferencesReader {

  private final PreferenceQueryService preferenceQueryService;

  public PreferenceSoftPreferencesReader(PreferenceQueryService preferenceQueryService) {
    this.preferenceQueryService = preferenceQueryService;
  }

  @Override
  public List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds) {
    return preferenceQueryService.getSoftPreferencesByUserIds(userIds);
  }
}
