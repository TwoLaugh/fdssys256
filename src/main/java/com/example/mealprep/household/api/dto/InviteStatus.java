package com.example.mealprep.household.api.dto;

/**
 * Derived status of a {@code HouseholdInvite}, computed from {@code acceptedAt} / {@code revokedAt}
 * / {@code expiresAt} at mapping time. Never persisted.
 *
 * <ul>
 *   <li>{@link #REVOKED} — {@code revokedAt != null}.
 *   <li>{@link #ACCEPTED} — {@code acceptedAt != null} and not revoked.
 *   <li>{@link #EXPIRED} — {@code expiresAt < now} and neither accepted nor revoked.
 *   <li>{@link #PENDING} — none of the above.
 * </ul>
 */
public enum InviteStatus {
  PENDING,
  ACCEPTED,
  REVOKED,
  EXPIRED
}
