package com.example.mealprep.discovery.validation;

import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Locale;
import java.util.Set;

/**
 * {@link ValidDiscoveryConstraints} implementation. Each rule emits a separate violation so the
 * client sees every failing field in one round trip (not just the first). Per ticket invariant 29.
 */
public class DiscoveryConstraintsValidator
    implements ConstraintValidator<ValidDiscoveryConstraints, DiscoveryConstraints> {

  private static final Set<String> MEAL_TYPES = Set.of("breakfast", "lunch", "dinner", "snack");
  private static final int SUPPORTED_SCHEMA_VERSION = 1;

  @Override
  public boolean isValid(DiscoveryConstraints value, ConstraintValidatorContext ctx) {
    if (value == null) {
      // @NotNull on the request field handles missing; this validator returns true to avoid
      // overlapping "must not be null" violations.
      return true;
    }
    boolean ok = true;
    ctx.disableDefaultConstraintViolation();

    if (value.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
      ctx.buildConstraintViolationWithTemplate(
              "unsupported schema version: " + value.schemaVersion())
          .addPropertyNode("schemaVersion")
          .addConstraintViolation();
      ok = false;
    }

    if (value.requiredMealTypes() != null) {
      for (String mt : value.requiredMealTypes()) {
        if (mt == null || !MEAL_TYPES.contains(mt)) {
          ctx.buildConstraintViolationWithTemplate("unknown meal type: " + mt)
              .addPropertyNode("requiredMealTypes")
              .addConstraintViolation();
          ok = false;
        }
      }
    }

    if (value.maxTotalTimeMins() != null && value.maxTotalTimeMins() < 0) {
      ctx.buildConstraintViolationWithTemplate("maxTotalTimeMins must be non-negative")
          .addPropertyNode("maxTotalTimeMins")
          .addConstraintViolation();
      ok = false;
    }

    if (value.mustExcludeIngredientMappingKeys() != null) {
      for (String k : value.mustExcludeIngredientMappingKeys()) {
        if (k == null
            || !k.equals(k.trim())
            || !k.equals(k.toLowerCase(Locale.ROOT))
            || k.isEmpty()) {
          ctx.buildConstraintViolationWithTemplate(
                  "ingredientMappingKey must be lowercase and trimmed: '" + k + "'")
              .addPropertyNode("mustExcludeIngredientMappingKeys")
              .addConstraintViolation();
          ok = false;
        }
      }
    }

    return ok;
  }
}
