package com.example.mealprep.recipe.validation;

import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Implementation of {@link ValidMethodSteps}. */
public class ValidMethodStepsValidator
    implements ConstraintValidator<ValidMethodSteps, List<CreateMethodStepRequest>> {

  @Override
  public boolean isValid(List<CreateMethodStepRequest> value, ConstraintValidatorContext context) {
    if (value == null || value.isEmpty()) {
      return true;
    }
    Set<Integer> seen = new HashSet<>();
    int max = Integer.MIN_VALUE;
    for (CreateMethodStepRequest step : value) {
      if (step == null) {
        continue;
      }
      int num = step.stepNumber();
      if (!seen.add(num)) {
        return false;
      }
      if (num > max) {
        max = num;
      }
    }
    // Contiguous starting at 1: must equal {1..size()}.
    if (max != value.size()) {
      return false;
    }
    for (int i = 1; i <= value.size(); i++) {
      if (!seen.contains(i)) {
        return false;
      }
    }
    return true;
  }
}
