package com.example.mealprep.household.exception;

import java.util.UUID;

/**
 * Thrown when a settings document is read or updated for a household that has no {@code
 * household_settings} row. Maps to HTTP 404 with type {@code .../household-settings-not-found}.
 *
 * <p>01a-era households (created before 01b shipped) are also surfaced as 404 — the user must
 * {@code PUT /settings} once to seed before subsequent {@code GET}s succeed.
 */
public class HouseholdSettingsNotFoundException extends HouseholdException {

  public HouseholdSettingsNotFoundException() {
    super("No settings document found for this household");
  }

  public HouseholdSettingsNotFoundException(UUID householdId) {
    super("No settings document found for household " + householdId);
  }
}
