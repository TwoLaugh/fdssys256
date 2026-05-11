package com.example.mealprep.provisions.validation;

import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementation of {@link ValidWasteQuantity}. Shape-only — no DB lookup. The cross-resource
 * quantity-vs-inventory check is service-side, see {@code WasteExceedsInventoryException}.
 */
public class WasteQuantityValidator
    implements ConstraintValidator<ValidWasteQuantity, LogWasteRequest> {

  @Override
  public boolean isValid(LogWasteRequest value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    // Rule: quantity != null ⇒ unit != null.
    if (value.quantity() != null && (value.unit() == null || value.unit().isBlank())) {
      return false;
    }
    return true;
  }
}
