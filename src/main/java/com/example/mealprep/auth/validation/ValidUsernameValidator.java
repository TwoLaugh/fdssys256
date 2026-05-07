package com.example.mealprep.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/** Implements {@link ValidUsername}. Pattern matches the OpenAPI spec exactly. */
public class ValidUsernameValidator implements ConstraintValidator<ValidUsername, String> {

  private static final Pattern PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return false;
    }
    return PATTERN.matcher(value).matches();
  }
}
