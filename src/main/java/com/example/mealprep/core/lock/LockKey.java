package com.example.mealprep.core.lock;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed key for {@link LockService#tryAcquire}. Sealed so all lock scopes are explicit; each
 * permitted record produces a stable serialised form so two semantically-equal keys always hash to
 * the same value.
 *
 * <p>Add a new permitted record when a new lock scope appears; do not extend with a generic
 * stringly-typed key.
 */
public sealed interface LockKey {

  /** Stable wire form. Used as input to the hashing helper. */
  String serialize();

  /**
   * Lock the weekly plan generation for one household-week. Used by the planner module to enforce
   * single-flight per {@code (household, week)}.
   */
  static LockKey forPlanWeek(UUID householdId, LocalDate weekStart) {
    return new ForPlanWeek(householdId, weekStart);
  }

  /**
   * Lock a specific recipe — used by the adaptation pipeline to prevent two adaptation jobs racing
   * on the same recipe.
   */
  static LockKey forRecipe(UUID recipeId) {
    return new ForRecipe(recipeId);
  }

  /**
   * Custom scope for callers that don't fit the named scopes. {@code scopeKind} should be
   * lowercase-hyphenated; {@code scopeId} identifies the specific instance.
   */
  static LockKey forCustom(String scopeKind, UUID scopeId) {
    return new ForCustom(scopeKind, scopeId);
  }

  record ForPlanWeek(UUID householdId, LocalDate weekStart) implements LockKey {
    public ForPlanWeek {
      Objects.requireNonNull(householdId, "householdId");
      Objects.requireNonNull(weekStart, "weekStart");
    }

    @Override
    public String serialize() {
      return "plan-week|" + householdId + "|" + weekStart;
    }
  }

  record ForRecipe(UUID recipeId) implements LockKey {
    public ForRecipe {
      Objects.requireNonNull(recipeId, "recipeId");
    }

    @Override
    public String serialize() {
      return "recipe|" + recipeId;
    }
  }

  record ForCustom(String scopeKind, UUID scopeId) implements LockKey {
    public ForCustom {
      Objects.requireNonNull(scopeKind, "scopeKind");
      Objects.requireNonNull(scopeId, "scopeId");
      if (scopeKind.isBlank()) {
        throw new IllegalArgumentException("scopeKind must not be blank");
      }
    }

    @Override
    public String serialize() {
      return "custom|" + scopeKind + "|" + scopeId;
    }
  }
}
