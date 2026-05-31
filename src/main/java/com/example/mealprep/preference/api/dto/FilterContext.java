package com.example.mealprep.preference.api.dto;

/**
 * Call context for a hard-constraint filter check. Mirrors the {@code context} taxonomy on a stored
 * {@link com.example.mealprep.preference.domain.entity.DietaryIdentityException} ({@code any},
 * {@code social}, {@code weekend}, {@code weekday}) and lets the filter evaluate a
 * context-conditional dietary-identity exception against the slot the check is running for.
 *
 * <p>Per {@code lld/preference.md} Flow 2 step 5: a conditional exception widens the base diet
 * <em>only when its {@code context} matches the call context</em>. An exception stored with {@code
 * context = "any"} widens unconditionally; an exception stored with {@code context = "weekend"}
 * widens only when the caller passes {@link #WEEKEND}.
 *
 * <p>{@link #ANY} is the conservative default callers pass when they have no richer context (or
 * cannot derive a weekday/weekend/social distinction): in that case only universally-applicable
 * ({@code context = "any"}) exceptions widen the base. This is the safe choice — a weekend-only
 * relaxation must not silently apply to a check whose context is unknown.
 */
public enum FilterContext {
  /** No specific context — only {@code context = "any"} exceptions widen the base. */
  ANY,
  /** A social/dining-out occasion. */
  SOCIAL,
  /** A weekend day. */
  WEEKEND,
  /** A weekday. */
  WEEKDAY;

  /** The lowercase token stored in {@code preference_dietary_identity_exceptions.context}. */
  public String token() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
