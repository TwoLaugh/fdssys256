package com.example.mealprep.preference.validation;

import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyMode;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyTolerance;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Map;
import java.util.Set;

/**
 * {@link ValidNoveltyTolerance} implementation. Each rule emits a separate violation so the client
 * sees every failing entry in one round trip (not just the first), matching the pattern set by
 * {@code DiscoveryConstraintsValidator}.
 */
public class NoveltyToleranceValidator
    implements ConstraintValidator<ValidNoveltyTolerance, NoveltyTolerance> {

  static final String MODE_ROTATION = "rotation";
  static final String MODE_BATCH_REPEAT = "batch_repeat";
  static final String MODE_HIGH_VARIETY = "high_variety";
  static final String MODE_STATIC = "static";

  private static final Set<String> KNOWN_MODES =
      Set.of(MODE_ROTATION, MODE_BATCH_REPEAT, MODE_HIGH_VARIETY, MODE_STATIC);

  @Override
  public boolean isValid(NoveltyTolerance value, ConstraintValidatorContext ctx) {
    if (value == null) {
      // Section-level @Valid handles nullability; the section itself is optional.
      return true;
    }
    boolean ok = true;
    ctx.disableDefaultConstraintViolation();

    if (value.bySlot() != null) {
      for (Map.Entry<String, NoveltyMode> e : value.bySlot().entrySet()) {
        String slot = e.getKey();
        NoveltyMode mode = e.getValue();
        if (mode == null) {
          ok = violation(ctx, "bySlot[" + slot + "] is null", "bySlot") && ok;
          continue;
        }
        if (mode.mode() == null || !KNOWN_MODES.contains(mode.mode())) {
          ok =
              violation(
                      ctx,
                      "unknown mode for slot '" + slot + "': " + mode.mode(),
                      "bySlot[" + slot + "].mode")
                  && ok;
          continue;
        }
        ok = validateModeFields(ctx, slot, mode) && ok;
      }
    }

    if (value.recipeRepeatCooldownWeeks() != null) {
      for (Map.Entry<String, Integer> e : value.recipeRepeatCooldownWeeks().entrySet()) {
        Integer v = e.getValue();
        if (v == null || v < 0) {
          ok =
              violation(
                      ctx,
                      "recipeRepeatCooldownWeeks[" + e.getKey() + "] must be >= 0",
                      "recipeRepeatCooldownWeeks[" + e.getKey() + "]")
                  && ok;
        }
      }
    }

    if (value.ingredientFrequencyCaps() != null) {
      for (String key : value.ingredientFrequencyCaps().keySet()) {
        if (key == null || key.isBlank()) {
          ok =
              violation(
                      ctx,
                      "ingredientFrequencyCaps key must be non-blank",
                      "ingredientFrequencyCaps")
                  && ok;
        }
      }
    }

    return ok;
  }

  private boolean validateModeFields(ConstraintValidatorContext ctx, String slot, NoveltyMode m) {
    boolean ok = true;
    switch (m.mode()) {
      case MODE_ROTATION -> {
        if (m.rotationSize() == null || m.rotationSize() <= 0) {
          ok =
              violation(
                  ctx,
                  "rotation mode requires rotationSize > 0 for slot '" + slot + "'",
                  "bySlot[" + slot + "].rotationSize");
        }
      }
      case MODE_BATCH_REPEAT -> {
        if (m.maxConsecutiveSame() == null || m.maxConsecutiveSame() <= 0) {
          ok =
              violation(
                  ctx,
                  "batch_repeat mode requires maxConsecutiveSame > 0 for slot '" + slot + "'",
                  "bySlot[" + slot + "].maxConsecutiveSame");
        }
      }
      case MODE_HIGH_VARIETY -> {
        if (m.newPerWeek() == null || m.newPerWeek() <= 0) {
          ok =
              violation(
                  ctx,
                  "high_variety mode requires newPerWeek > 0 for slot '" + slot + "'",
                  "bySlot[" + slot + "].newPerWeek");
        }
      }
      case MODE_STATIC -> {
        if (m.rotationSize() != null || m.maxConsecutiveSame() != null || m.newPerWeek() != null) {
          ok =
              violation(
                  ctx,
                  "static mode accepts no mode-specific fields for slot '" + slot + "'",
                  "bySlot[" + slot + "]");
        }
      }
      default -> {
        // Unreachable — KNOWN_MODES gate above.
      }
    }
    return ok;
  }

  private static boolean violation(ConstraintValidatorContext ctx, String message, String node) {
    ctx.buildConstraintViolationWithTemplate(message)
        .addPropertyNode(node)
        .addConstraintViolation();
    return false;
  }
}
