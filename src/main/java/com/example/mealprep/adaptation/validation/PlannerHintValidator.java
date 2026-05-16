package com.example.mealprep.adaptation.validation;

import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator backing {@link ValidPlannerHint}. Asserts shape requirements per the {@code hintType}
 * discriminator. {@code null} payloads pass through — the field-level {@code @NotNull} on {@code
 * PlannerHintRequest.payload} already covers that.
 */
public class PlannerHintValidator
    implements ConstraintValidator<ValidPlannerHint, PlannerHintRequest> {

  @Override
  public boolean isValid(PlannerHintRequest value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    HintType type = value.hintType();
    JsonNode payload = value.payload();
    if (type == null || payload == null) {
      // Field-level @NotNull handles these; let those constraints surface, not this one.
      return true;
    }
    return switch (type) {
      case PREP_LEAD_TIME -> hasPositiveInt(payload, "lead_time_hours");
      case ABSORPTION_CONFLICT -> hasNonEmptyString(payload, "blocked_by");
      default -> true;
    };
  }

  private static boolean hasPositiveInt(JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || !node.canConvertToInt()) {
      return false;
    }
    return node.asInt() > 0;
  }

  private static boolean hasNonEmptyString(JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    return node != null && node.isTextual() && !node.asText().isBlank();
  }
}
