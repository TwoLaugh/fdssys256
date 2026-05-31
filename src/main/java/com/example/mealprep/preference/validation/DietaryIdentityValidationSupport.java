package com.example.mealprep.preference.validation;

import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityExceptionDto;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;

/**
 * Shared shape-validation logic for {@link DietaryIdentityValidator} and {@link
 * DietaryIdentityRequestValidator}: base-membership, exception sub-category membership, and
 * exception-context membership. The allergy/intolerance collision check lives in {@link
 * DietaryIdentityRequestValidator} because it needs the sibling allergy/intolerance lists.
 */
final class DietaryIdentityValidationSupport {

  private DietaryIdentityValidationSupport() {}

  /**
   * Validates the base diet, every exception's {@code allows} sub-category, and every exception's
   * {@code context}. Returns {@code true} when all pass; emits one violation per failure otherwise.
   */
  static boolean validateShape(DietaryIdentityDto value, ConstraintValidatorContext ctx) {
    boolean ok = true;
    if (!DietaryIdentityRules.isKnownBase(value.base())) {
      ok = violation(ctx, "unknown dietary base: " + value.base(), "base");
    }
    List<DietaryIdentityExceptionDto> exceptions = value.exceptions();
    if (exceptions != null) {
      for (int i = 0; i < exceptions.size(); i++) {
        DietaryIdentityExceptionDto ex = exceptions.get(i);
        if (ex == null) {
          continue; // @Valid on the list handles element nullability.
        }
        if (!DietaryIdentityRules.isKnownAllows(ex.allows())) {
          ok =
              violation(
                      ctx,
                      "unknown exception sub-category: " + ex.allows(),
                      "exceptions[" + i + "].allows")
                  && ok;
        }
        if (!DietaryIdentityRules.isKnownContext(ex.context())) {
          ok =
              violation(
                      ctx,
                      "unknown exception context: " + ex.context(),
                      "exceptions[" + i + "].context")
                  && ok;
        }
      }
    }
    return ok;
  }

  static boolean violation(ConstraintValidatorContext ctx, String message, String node) {
    ctx.disableDefaultConstraintViolation();
    ctx.buildConstraintViolationWithTemplate(message)
        .addPropertyNode(node)
        .addConstraintViolation();
    return false;
  }
}
