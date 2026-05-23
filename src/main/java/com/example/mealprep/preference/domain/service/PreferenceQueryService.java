package com.example.mealprep.preference.domain.service;

import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.preference.api.dto.HardConstraintsAuditEntryDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the preference module — partial in 01a (hard-constraints only). Taste profile,
 * lifestyle config, profile metadata, and the soft bundle land in subsequent preference tickets.
 */
public interface PreferenceQueryService {

  /** Returns the user's hard-constraints aggregate, or empty if not yet initialised. */
  Optional<HardConstraintsDto> getHardConstraints(UUID userId);

  /**
   * Paginated audit-log entries for the user's hard-constraints aggregate, newest-first. Returns
   * empty page if the user has no aggregate row.
   */
  Page<HardConstraintsAuditEntryDto> getHardConstraintsAuditLog(UUID userId, Pageable pageable);

  /**
   * The user's non-vector soft-preference bundle (ingredient/cuisine like-scores, avoid list, and
   * the lifestyle window/novelty/batch flags the household merge consumes), or empty if the user
   * has neither a taste profile nor a lifestyle config.
   *
   * <p>NON-VECTOR only — the taste vector / embedding is intentionally excluded (it belongs to the
   * deferred embedding vertical, and the safety-critical hard-constraint read path stays separate).
   */
  Optional<SoftPreferenceBundleDto> getSoftPreferences(UUID userId);

  /**
   * One soft-pref bundle per input user, in input order (callers index by position). Returns a
   * null-fielded bundle for a user with no taste profile / lifestyle config. The returned list
   * ALWAYS has {@code userIds.size()} elements in the same order — never fewer. NON-VECTOR only
   * (see {@link #getSoftPreferences(UUID)}).
   */
  List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds);
}
