package com.example.mealprep.household.domain.service;

import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.UpdateHouseholdSettingsRequest;
import java.util.UUID;

/**
 * Write API for the household module. 01a shipped {@link #createHousehold}; 01b adds {@link
 * #updateSettings}. Member CRUD, role-change, invite accept, and merge land in subsequent
 * sub-tickets.
 */
public interface HouseholdUpdateService {

  /**
   * Create a new household and seat the creator as its first {@code primary} member in a single
   * transaction. Throws {@code UserAlreadyInHouseholdException} if the creator is already a member
   * of any household.
   */
  HouseholdDto createHousehold(UUID creatorUserId, CreateHouseholdRequest request);

  /**
   * Replace a household's settings document (primary-only). The version-checked write computes a
   * per-section diff, writes one audit row per changed {@code fieldPath}, and publishes {@code
   * HouseholdSettingsChangedEvent} {@code AFTER_COMMIT}. No-op replacements (re-submit of an
   * identical document) write no audit rows and emit no event.
   *
   * <p>Throws {@code InsufficientHouseholdRoleException} (403) if the caller is not the household's
   * {@code primary} (or is not a member at all); {@code HouseholdSettingsNotFoundException} (404)
   * if there is no settings row; {@code OptimisticLockingFailureException} (409) on stale {@code
   * expectedVersion}.
   */
  HouseholdSettingsDto updateSettings(
      UUID householdId, UUID actorUserId, UpdateHouseholdSettingsRequest request);
}
