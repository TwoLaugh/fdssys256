package com.example.mealprep.recipe.validation;

import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Implementation of {@link ValidRecipeMetadata}. */
public class ValidRecipeMetadataValidator
    implements ConstraintValidator<ValidRecipeMetadata, CreateRecipeMetadataRequest> {

  @Override
  public boolean isValid(CreateRecipeMetadataRequest value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    int expected = value.prepTimeMins() + value.cookTimeMins();
    return Math.abs(value.totalTimeMins() - expected) <= 1;
  }
}
