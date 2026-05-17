package com.example.mealprep.feedback.validation;

import com.example.mealprep.feedback.spi.Destination;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.EnumSet;
import java.util.Set;

/**
 * Implements {@link ValidDestination}: the value must be one of the four recognised {@link
 * Destination} enum constants. Null is rejected (the {@code @NotNull} on the request field also
 * catches it — belt-and-braces per ticket 01f §2). The structural validity check lives in the
 * service flow, not here.
 */
public class ValidDestinationValidator
    implements ConstraintValidator<ValidDestination, Destination> {

  private static final Set<Destination> RECOGNISED = EnumSet.allOf(Destination.class);

  @Override
  public boolean isValid(Destination value, ConstraintValidatorContext ctx) {
    return value != null && RECOGNISED.contains(value);
  }
}
