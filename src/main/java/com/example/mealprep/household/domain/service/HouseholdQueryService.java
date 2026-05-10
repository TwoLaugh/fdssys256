package com.example.mealprep.household.domain.service;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsAuditEntryDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.SlotConfigurationDto;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the household module. 01a shipped {@link #getById}/{@link #getByUserId}; 01b adds
 * settings + slot-configuration + audit-log readers. Invites + merge land in 01c/01d.
 */
public interface HouseholdQueryService {

  /** Look up a household by id, with members eagerly populated. */
  Optional<HouseholdDto> getById(UUID householdId);

  /**
   * Read-by-others contract: return the (single) household this user belongs to, or empty if they
   * are not in any household. v1 invariant: at most one household per user.
   */
  Optional<HouseholdDto> getByUserId(UUID userId);

  /**
   * Look up the settings document for a household. Returns empty when no row exists (01a-era
   * household, lazy-on-first-PUT). Authorisation: caller must be a member; non-member callers see
   * empty (the controller maps to 404 to avoid leaking existence).
   */
  Optional<HouseholdSettingsDto> getSettings(UUID householdId, UUID callerUserId);

  /**
   * Paginated audit-log of changes to a household's settings, newest-first. Authorisation: caller
   * must be a member (any role). Throws {@code HouseholdSettingsNotFoundException} if there is no
   * settings row for the target household.
   */
  Page<HouseholdSettingsAuditEntryDto> getSettingsAuditLog(
      UUID householdId, UUID callerUserId, Pageable pageable);

  /**
   * Resolve the household's slot configuration (defaults + custom slots + eater lists).
   * Authorisation: caller must be a member. Throws {@code HouseholdSettingsNotFoundException} if no
   * settings row exists, {@code HouseholdNotFoundException} if the household itself is missing.
   */
  SlotConfigurationDto getSlotConfiguration(UUID householdId, UUID callerUserId);
}
