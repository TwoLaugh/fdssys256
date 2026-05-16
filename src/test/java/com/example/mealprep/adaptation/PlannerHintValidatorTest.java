package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
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
