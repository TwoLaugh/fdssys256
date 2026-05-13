package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.DirectiveKind;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.api.dto.PlanConstraintsSnapshotDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Jakarta-validation smoke for every adaptation-pipeline request DTO. Confirms the {@code @NotNull
 * / @NotBlank / @Size} annotations on the LLD-listed fields fire when callers pass invalid input.
 *
 * <p>Per ticket-01b §Edge-case checklist line "Jakarta validation smoke".
 */
class AdaptationDtoValidationTest {

  private static final Validator VALIDATOR;

  static {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      VALIDATOR = factory.getValidator();
    }
  }

  @Test
  void import_job_request_null_recipe_id_is_one_violation() {
    ImportJobRequest req =
        new ImportJobRequest(
            null,
            UUID.randomUUID(),
            com.example.mealprep.recipe.domain.entity.Catalogue.USER,
            com.example.mealprep.recipe.domain.entity.DataQuality.AI_GENERATED,
            null,
            null);
    var violations = VALIDATOR.validate(req);
    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("recipeId");
  }

  @Test
  void import_job_request_valid_passes() {
    ImportJobRequest req =
        new ImportJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            com.example.mealprep.recipe.domain.entity.Catalogue.USER,
            com.example.mealprep.recipe.domain.entity.DataQuality.AI_GENERATED,
            null,
            null);
    assertThat(VALIDATOR.validate(req)).isEmpty();
  }

  @Test
  void data_model_job_request_over_5000_affected_recipes_violates() {
    Set<UUID> tooMany = new HashSet<>();
    for (int i = 0; i < 5001; i++) {
      tooMany.add(UUID.randomUUID());
    }
    DataModelJobRequest req =
        new DataModelJobRequest(
            UUID.randomUUID(),
            DataModelChangeType.PREFERENCE,
            JsonNodeFactory.instance.objectNode(),
            tooMany,
            UUID.randomUUID());
    var violations = VALIDATOR.validate(req);
    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getPropertyPath().toString())
        .isEqualTo("affectedRecipeIds");
  }

  @Test
  void data_model_job_request_at_cap_passes() {
    Set<UUID> exactly5000 = new HashSet<>();
    for (int i = 0; i < 5000; i++) {
      exactly5000.add(UUID.randomUUID());
    }
    DataModelJobRequest req =
        new DataModelJobRequest(
            UUID.randomUUID(),
            DataModelChangeType.HARD_CONSTRAINTS,
            JsonNodeFactory.instance.objectNode(),
            exactly5000,
            UUID.randomUUID());
    assertThat(VALIDATOR.validate(req)).isEmpty();
  }

  @Test
  void feedback_job_request_null_rating_delta_violates() {
    FeedbackJobRequest req =
        new FeedbackJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "tastes flat",
            null,
            UUID.randomUUID(),
            null);
    var violations = VALIDATOR.validate(req);
    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("ratingDelta");
  }

  @Test
  void planner_hint_request_blank_description_violates() {
    PlannerHintRequest req =
        new PlannerHintRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            HintType.PREP_LEAD_TIME,
            "",
            JsonNodeFactory.instance.objectNode(),
            HintSeverity.INFO,
            null,
            UUID.randomUUID());
    var violations = VALIDATOR.validate(req);
    assertThat(violations).isNotEmpty();
    assertThat(violations.stream().map(v -> v.getPropertyPath().toString()).toList())
        .contains("description");
  }

  @Test
  void planner_hint_request_description_over_500_chars_violates() {
    PlannerHintRequest req =
        new PlannerHintRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            HintType.PREP_LEAD_TIME,
            "a".repeat(501),
            JsonNodeFactory.instance.objectNode(),
            HintSeverity.INFO,
            null,
            UUID.randomUUID());
    var violations = VALIDATOR.validate(req);
    assertThat(violations).isNotEmpty();
    assertThat(violations.stream().map(v -> v.getPropertyPath().toString()).toList())
        .contains("description");
  }

  @Test
  void reject_pending_change_request_over_200_chars_violates() {
    RejectPendingChangeRequest req = new RejectPendingChangeRequest("a".repeat(201));
    var violations = VALIDATOR.validate(req);
    assertThat(violations).isNotEmpty();
  }

  @Test
  void reject_pending_change_request_null_reason_note_passes() {
    // No @NotBlank — dismissal without a note is allowed.
    assertThat(VALIDATOR.validate(new RejectPendingChangeRequest(null))).isEmpty();
  }

  @Test
  void plan_time_refine_directive_request_valid_construction() {
    // Confirms a fixture record can be constructed cleanly (per checklist last item).
    PlanTimeRefineDirectiveRequest req =
        new PlanTimeRefineDirectiveRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new PlanTimeRefineDirectiveRequest.RefineDirectiveDto(
                DirectiveKind.COST_DELTA, "drop £2", JsonNodeFactory.instance.objectNode()),
            new PlanConstraintsSnapshotDto(
                JsonNodeFactory.instance.objectNode(),
                BigDecimal.valueOf(40),
                Set.of("oven"),
                Map.of("protein_g", BigDecimal.valueOf(120)),
                Instant.now()),
            UUID.randomUUID(),
            UUID.randomUUID());
    assertThat(VALIDATOR.validate(req)).isEmpty();
  }

  @Test
  void accept_pending_change_request_null_user_edits_passes() {
    // userEdits is @Nullable — bare accept (no overlay) must validate cleanly.
    assertThat(VALIDATOR.validate(new AcceptPendingChangeRequest(null, 1L))).isEmpty();
  }
}
