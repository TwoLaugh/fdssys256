package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.validation.PlannerHintValidator;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Asserts the {@code @ValidPlannerHint} class-level constraint enforces the payload-shape contract
 * per ticket §step 44.
 */
class PlannerHintValidatorTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void prep_lead_time_with_positive_int_passes() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("lead_time_hours", 24);
    PlannerHintRequest req = request(HintType.PREP_LEAD_TIME, payload);
    assertThat(validator.validate(req)).isEmpty();
  }

  @Test
  void prep_lead_time_without_field_fails() {
    PlannerHintRequest req =
        request(HintType.PREP_LEAD_TIME, JsonNodeFactory.instance.objectNode());
    assertThat(validator.validate(req)).isNotEmpty();
  }

  @Test
  void prep_lead_time_with_zero_value_fails() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("lead_time_hours", 0);
    PlannerHintRequest req = request(HintType.PREP_LEAD_TIME, payload);
    assertThat(validator.validate(req)).isNotEmpty();
  }

  @Test
  void absorption_conflict_with_blocked_by_passes() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("blocked_by", "spinach");
    PlannerHintRequest req = request(HintType.ABSORPTION_CONFLICT, payload);
    assertThat(validator.validate(req)).isEmpty();
  }

  @Test
  void absorption_conflict_without_field_fails() {
    PlannerHintRequest req =
        request(HintType.ABSORPTION_CONFLICT, JsonNodeFactory.instance.objectNode());
    assertThat(validator.validate(req)).isNotEmpty();
  }

  @Test
  void nutrition_tradeoff_payload_is_free_form() {
    PlannerHintRequest req =
        request(HintType.NUTRITION_TRADEOFF, JsonNodeFactory.instance.objectNode());
    assertThat(validator.validate(req)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Null-passthrough arms (PIT NO_COVERAGE, BooleanFalseReturnVals): L20
  // `value == null -> true` and L26 `type == null || payload == null -> true`.
  // The bean Validator never drives these (field-level @NotNull short-circuits),
  // so call the ConstraintValidator directly. A `return false` mutant flips
  // these passes to failures.
  // ---------------------------------------------------------------------------

  @Test
  void null_request_value_is_valid_passthrough() {
    PlannerHintValidator v = new PlannerHintValidator();
    assertThat(v.isValid(null, null)).isTrue();
  }

  @Test
  void null_hint_type_defers_to_field_level_notnull_and_passes_here() {
    PlannerHintValidator v = new PlannerHintValidator();
    PlannerHintRequest req = request(null, JsonNodeFactory.instance.objectNode());
    assertThat(v.isValid(req, null)).isTrue();
  }

  @Test
  void null_payload_defers_to_field_level_notnull_and_passes_here() {
    PlannerHintValidator v = new PlannerHintValidator();
    PlannerHintRequest req = request(HintType.PREP_LEAD_TIME, null);
    assertThat(v.isValid(req, null)).isTrue();
  }

  private static PlannerHintRequest request(HintType type, ObjectNode payload) {
    return new PlannerHintRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        type,
        "describe",
        payload,
        HintSeverity.INFO,
        null,
        UUID.randomUUID());
  }
}
