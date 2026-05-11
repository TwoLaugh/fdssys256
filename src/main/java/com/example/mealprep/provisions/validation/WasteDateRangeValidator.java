package com.example.mealprep.provisions.validation;

import com.example.mealprep.provisions.api.dto.WasteListQuery;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Implementation of {@link ValidWasteDateRange}. */
public class WasteDateRangeValidator
    implements ConstraintValidator<ValidWasteDateRange, WasteListQuery> {

  @Override
  public boolean isValid(WasteListQuery value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    if (value.from() == null || value.to() == null) {
      // @NotNull elsewhere when the field is mandatory; we don't double-report.
      return true;
    }
    return !value.from().isAfter(value.to());
  }
}
