package com.example.mealprep.household.spi;

import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import java.util.List;
import java.util.UUID;

/**
 * SPI looked up via {@code @Autowired Optional<SoftPreferencesReader>} by the household merge
 * service. Implementations live outside the household module (preference-01c will ship one); until
 * then, the household module's {@code NoopSoftPreferencesReader} satisfies the contract with an
 * empty result.
 *
 * <p>Worth user review: the SPI currently lives in household. When preference-01c ships, the
 * user/parent may prefer it relocated to {@code core/} (cross-module integration contract) or to
 * preference itself (which owns soft-prefs). Relocation is a mechanical rename + import update.
 */
public interface SoftPreferencesReader {

  /**
   * Return one {@link SoftPreferenceBundleDto} per input user id, in input order. Implementations
   * MAY return null-fielded bundles for users with no soft preferences yet; MUST NOT return fewer
   * bundles than userIds (callers index by position).
   *
   * <p>Until preference-01c lands a real implementation, {@code NoopSoftPreferencesReader} returns
   * {@code List.of()} — the merger handles that branch cleanly.
   */
  List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds);
}
