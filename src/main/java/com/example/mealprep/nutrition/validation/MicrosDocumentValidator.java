package com.example.mealprep.nutrition.validation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Iterator;
import java.util.Map;

/**
 * Schema gate for request-side micros documents, enforcing the contract's {@code
 * additionalProperties: number, minimum: 0}. Pure deterministic. Null passes (the field is
 * nullable); a non-object node, a non-numeric value, or a negative value fails with a message
 * naming the offending key.
 */
public class MicrosDocumentValidator implements ConstraintValidator<ValidMicros, JsonNode> {

  @Override
  public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
    if (value == null || value.isNull()) {
      return true;
    }
    if (!value.isObject()) {
      return violation(context, "micros must be a JSON object");
    }
    Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode v = field.getValue();
      if (v == null || !v.isNumber()) {
        return violation(context, "micros." + field.getKey() + " must be a number");
      }
      if (v.decimalValue().signum() < 0) {
        return violation(context, "micros." + field.getKey() + " must be >= 0");
      }
    }
    return true;
  }

  private static boolean violation(ConstraintValidatorContext context, String message) {
    context.disableDefaultConstraintViolation();
    context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
    return false;
  }
}
