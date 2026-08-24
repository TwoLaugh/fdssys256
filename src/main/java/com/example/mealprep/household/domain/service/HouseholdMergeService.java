package com.example.mealprep.household.domain.service;

import com.example.mealprep.household.api.dto.MergedSoftPreferencesDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only merge surface — computes the soft-preferences document the planner consumes during slot
 * composition. Result is transient; never persisted (LLD line 326).
 *
 * <p>Implemented by {@code HouseholdServiceImpl} per LLD line 262 (single-impl). Empty input is
 * handled cleanly: with no real {@code SoftPreferencesReader} bean wired (i.e. before
 * preference-01c), the result is an empty {@link MergedSoftPreferencesDto}.
 */
public interface HouseholdMergeService {

  /**
   * Merge soft preferences for the given household. {@code eaterUserIds} null or empty resolves to
   * all current members (LLD line 318); otherwise every supplied id MUST be a current member of the
   * household (LLD Flow 7 step 1). Throws {@code HouseholdNotFoundException} (404) if the household
   * is missing, {@code EmptyHouseholdMergeException} (422) if the household has zero members, and
   * {@code HouseholdMemberNotFoundException} (404) if any supplied {@code eaterUserId} is not a
   * current member.
   */
  MergedSoftPreferencesDto mergeSoftPreferencesForSlot(UUID householdId, List<UUID> eaterUserIds);

  /**
   * Best-effort variant of {@link #mergeSoftPreferencesForSlot} for in-process callers (the
   * planner): absence is a value, not an exception. Empty when the household is missing, has zero
   * members, or a supplied {@code eaterUserId} is not a current member. The throwing variant exists
   * for the REST seam, where those cases map to HTTP errors; this one exists so a caller inside an
   * open transaction never has an exception cross the service proxy and mark it rollback-only.
   */
  Optional<MergedSoftPreferencesDto> mergeSoftPreferencesForSlotIfResolvable(
      UUID householdId, List<UUID> eaterUserIds);

  /**
   * Variant for feasibility checks / tests: bypasses household lookup and uses the supplied
   * priorities directly. {@code userIds.size() == priorities.size()} required. Resulting DTO has
   * {@code householdId = null}.
   */
  MergedSoftPreferencesDto mergeSoftPreferencesForUsers(
      List<UUID> userIds, List<Integer> priorities);
}
