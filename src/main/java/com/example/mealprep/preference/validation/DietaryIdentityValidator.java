package com.example.mealprep.preference.validation;

import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ValidDietaryIdentity} implementation targeting {@link DietaryIdentityDto}. Validates the
 * base diet, each exception's sub-category, and each exception's context. The allergy/intolerance
 * collision check is NOT performed here (this type carries no allergy data) — see {@link
 * DietaryIdentityRequestValidator}.
 *
 * <p>Each failing rule emits a separate violation so the client sees them all in one round trip,
 * matching {@link NoveltyToleranceValidator}.
 */
public class DietaryIdentityValidator
    implements ConstraintValidator<ValidDietaryIdentity, DietaryIdentityDto> {

  @Override
  public boolean isValid(DietaryIdentityDto value, ConstraintValidatorContext ctx) {
    if (value == null) {
      // @NotNull on the field handles nullability.
      return true;
    }
    return DietaryIdentityValidationSupport.validateShape(value, ctx);
  }
}
