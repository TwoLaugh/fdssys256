package com.example.mealprep.recipe.validation;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Implementation of {@link ValidIngredientList}. */
public class ValidIngredientListValidator
    implements ConstraintValidator<ValidIngredientList, List<CreateIngredientRequest>> {

  @Override
  public boolean isValid(List<CreateIngredientRequest> value, ConstraintValidatorContext context) {
    if (value == null || value.isEmpty()) {
      return true;
    }
    Set<Integer> seen = new HashSet<>();
    for (CreateIngredientRequest ingredient : value) {
      if (ingredient == null) {
        continue;
      }
      if (!seen.add(ingredient.lineOrder())) {
        return false;
      }
    }
    return true;
  }
}
