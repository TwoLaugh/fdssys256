package com.example.mealprep.preference.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Full-replacement update for a user's hard-constraints aggregate. The {@code expectedVersion}
 * field is matched against the row's current {@code @Version}; mismatch → 409. Each child
 * collection is replaced wholesale (cascade + orphanRemoval handle delete + insert).
 *
 * <p>The {@code @ValidDietaryIdentity} cross-field validator is intentionally NOT applied here —
 * 01c will add it; today {@code dietaryIdentity.base} is just length-bounded.
 *
 * <p><b>Tier-1 removal safety gate (GAP-04).</b> Removing a safety-critical Tier-1 hard constraint
 * (an allergy, a medical diet, a severe/hard intolerance, or narrowing the dietary-identity base)
 * is a two-step operation: the first PUT that would remove one is rejected with 409 + {@code
 * TIER1_REMOVAL_REQUIRES_CONFIRMATION} so the UI can render a confirmation interstitial; the client
 * re-submits the same payload with {@code confirmTier1Removals = true} to proceed. Additions,
 * reorderings, and non-Tier-1 edits never require the flag. The flag is optional in the wire
 * contract (absent ⇒ {@code false}), so ordinary one-step edits stay unchanged.
 */
public record UpdateHardConstraintsRequest(
    @NotNull List<@NotBlank @Size(max = 64) String> allergies,
    @NotNull @Valid DietaryIdentityDto dietaryIdentity,
    @NotNull List<@NotBlank @Size(max = 64) String> medicalDiets,
    @NotNull @Valid List<HardIntoleranceDto> intolerances,
    @NotNull @Valid List<AgeRestrictionDto> ageRestrictions,
    @Min(0) long expectedVersion,
    Boolean confirmTier1Removals) {

  /**
   * True only when the caller explicitly set the confirmation flag; absent/null ⇒ not confirmed.
   */
  public boolean tier1RemovalsConfirmed() {
    return Boolean.TRUE.equals(confirmTier1Removals);
  }
}
