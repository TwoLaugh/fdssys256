package com.example.mealprep.preference.validation;

import java.util.Locale;
import java.util.Set;

/**
 * Shared vocabulary for {@link DietaryIdentityValidator} and {@link
 * DietaryIdentityRequestValidator} — the known dietary bases, the known exception sub-categories,
 * and the known exception contexts.
 *
 * <p>Reference data, intentionally a code constant (not DB-backed): it is part of the v1 product
 * taxonomy, tiny, and consulted only at request-validation time. Mirrors the {@code base} values
 * {@code DietaryBaseExclusions} recognises plus the LLD's enumerated bases.
 */
final class DietaryIdentityRules {

  private DietaryIdentityRules() {}

  /** Recognised {@code dietaryIdentity.base} values (lowercase). */
  static final Set<String> KNOWN_BASES =
      Set.of("omnivore", "vegetarian", "vegan", "pescatarian", "keto", "paleo", "other");

  /**
   * Recognised exception {@code allows} sub-categories (lowercase). Conditional "X-free" qualifiers
   * (e.g. {@code lactose_free}) are accepted separately by {@link #isFreeOfQualifier(String)}.
   */
  static final Set<String> KNOWN_SUBCATEGORIES =
      Set.of(
          "fish",
          "shellfish",
          "poultry",
          "meat",
          "red_meat",
          "pork",
          "dairy",
          "eggs",
          "egg",
          "gluten",
          "honey",
          "gelatin");

  /** Recognised exception {@code context} tokens (lowercase). */
  static final Set<String> KNOWN_CONTEXTS = Set.of("any", "social", "weekend", "weekday");

  static final String FREE_OF_SUFFIX = "_free";

  static String norm(String s) {
    return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
  }

  /** A conditional qualifier like {@code lactose_free} / {@code gluten_free}. */
  static boolean isFreeOfQualifier(String allows) {
    String n = norm(allows);
    return n != null && n.endsWith(FREE_OF_SUFFIX) && n.length() > FREE_OF_SUFFIX.length();
  }

  static boolean isKnownAllows(String allows) {
    String n = norm(allows);
    return n != null && (KNOWN_SUBCATEGORIES.contains(n) || isFreeOfQualifier(n));
  }

  static boolean isKnownBase(String base) {
    String n = norm(base);
    return n != null && KNOWN_BASES.contains(n);
  }

  static boolean isKnownContext(String context) {
    // Null/blank context defaults to "any" at the column level, so treat it as valid.
    String n = norm(context);
    return n == null || n.isBlank() || KNOWN_CONTEXTS.contains(n);
  }
}
