package com.example.mealprep.auth.validation;

import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator;
import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator.Reason;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;

/**
 * Implements {@link ValidPassword}. Delegates to {@link PasswordStrengthValidator} so the rule set
 * stays in one place; the annotation cannot see the username so MATCHES_USERNAME is only checked
 * service-side.
 */
public class ValidPasswordValidator implements ConstraintValidator<ValidPassword, String> {

  private final PasswordStrengthValidator strengthValidator;

  public ValidPasswordValidator(PasswordStrengthValidator strengthValidator) {
    this.strengthValidator = strengthValidator;
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return false;
    }
    List<Reason> reasons = strengthValidator.evaluate(value, null);
    if (reasons.isEmpty()) {
      return true;
    }
    context.disableDefaultConstraintViolation();
    String firstReason = reasons.get(0).name();
    context.buildConstraintViolationWithTemplate(firstReason).addConstraintViolation();
    return false;
  }
}
