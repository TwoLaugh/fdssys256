package com.example.mealprep.adaptation.validation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Structural validator for recipe-diff JSON payloads. {@code null} is treated as valid (callers use
 * {@code null} to mean "no overlay"); the field-level {@code @Nullable} marker on {@code
 * AcceptPendingChangeRequest.userEdits} carries that intent.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Top-level must be a JSON object.
 *   <li>If {@code base_version_id} is present, it must be a non-blank string (UUID textually
 *       validated downstream).
 *   <li>If {@code ingredients} is present, every entry's {@code mapping_key} (when present) must be
 *       a non-blank string.
 * </ul>
 */
public class RecipeDiffValidator implements ConstraintValidator<ValidRecipeDiff, JsonNode> {

  @Override
  public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
    if (value == null || value.isNull()) {
      return true;
    }
    if (!value.isObject()) {
      return false;
    }
    JsonNode baseVersionId = value.get("base_version_id");
    if (baseVersionId != null && (!baseVersionId.isTextual() || baseVersionId.asText().isBlank())) {
      return false;
    }
    JsonNode ingredients = value.get("ingredients");
    if (ingredients != null && ingredients.isArray()) {
      for (JsonNode ing : ingredients) {
        JsonNode key = ing.get("mapping_key");
        if (key != null && (!key.isTextual() || key.asText().isBlank())) {
          return false;
        }
      }
    }
    return true;
  }
}
