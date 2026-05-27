package com.example.mealprep.core.ingredient;

import java.util.Locale;

/**
 * Shared, cross-module normaliser for {@code ingredient_mapping_key} values. Lowercase + trim +
 * collapse internal whitespace to a single space, so "Chicken Breast" and "chicken breast" resolve
 * to the same key across modules.
 *
 * <p>Per {@code design/technical-architecture.md} §Cross-module references: "Always lowercase,
 * trimmed. … All modules must use it before storing or looking up keys." This is the single source
 * of truth for that contract — nutrition's {@code IntakeKeyNormaliser} delegates here, and
 * provisions / recipe / grocery call it at every key write and lookup boundary.
 *
 * <p>Pure static, zero dependencies (no Spring, no other module) — {@code core} stays a leaf
 * module. Locale is pinned to {@link Locale#ROOT} so lowercasing is deterministic regardless of the
 * deployment's default locale (avoids the Turkish-i class of bugs on machine-comparable keys).
 */
public final class IngredientMappingKeys {

  private IngredientMappingKeys() {}

  /**
   * Lowercase + trim + collapse internal whitespace to a single space. {@code null} → {@code null};
   * empty/whitespace-only → {@code ""}. Idempotent: {@code normalise(normalise(x))} equals {@code
   * normalise(x)}.
   */
  public static String normalise(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    return trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
}
