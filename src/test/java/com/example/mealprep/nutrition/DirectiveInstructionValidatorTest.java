package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.DirectiveDurationDto;
import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectivePhaseDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code @ValidDirectiveInstruction} schema gate ({@link
 * com.example.mealprep.nutrition.validation.DirectiveInstructionValidator}). Drives the real
 * Hibernate Validator engine so the violation-message + property-path building is exercised (not
 * just the boolean outcome). Pure deterministic logic — no Spring context.
 */
class DirectiveInstructionValidatorTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private Set<ConstraintViolation<DirectiveInstructionDocument>> validate(
      DirectiveInstructionDocument doc) {
    return validator.validate(doc);
  }

  private DirectiveInstructionDocument doc(
      String action, String target, DirectiveDurationDto duration) {
    return new DirectiveInstructionDocument(action, target, "global", duration, null);
  }

  // ---------- action rule ----------

  @Test
  void all_known_actions_without_extra_rules_pass() {
    // rebalance_macros / eliminate_then_reintroduce / downgrade_sensitivity need no target.
    assertThat(validate(doc("rebalance_macros", null, null))).isEmpty();
    assertThat(validate(doc("eliminate_then_reintroduce", null, null))).isEmpty();
    assertThat(validate(doc("downgrade_sensitivity", null, null))).isEmpty();
  }

  @Test
  void null_action_is_rejected_with_action_path() {
    Set<ConstraintViolation<DirectiveInstructionDocument>> v = validate(doc(null, null, null));
    assertThat(v).hasSize(1);
    ConstraintViolation<DirectiveInstructionDocument> cv = v.iterator().next();
    assertThat(cv.getPropertyPath().toString()).isEqualTo("action");
    assertThat(cv.getMessage()).startsWith("action must be one of");
  }

  @Test
  void unknown_action_is_rejected() {
    Set<ConstraintViolation<DirectiveInstructionDocument>> v =
        validate(doc("teleport_ingredient", null, null));
    assertThat(v).hasSize(1);
    assertThat(v.iterator().next().getPropertyPath().toString()).isEqualTo("action");
  }

  // ---------- target-required rule ----------

  @Test
  void restrict_ingredient_requires_non_blank_target() {
    assertThat(validate(doc("restrict_ingredient", "peanuts", null))).isEmpty();

    Set<ConstraintViolation<DirectiveInstructionDocument>> missing =
        validate(doc("restrict_ingredient", null, null));
    assertThat(missing).hasSize(1);
    assertThat(missing.iterator().next().getPropertyPath().toString()).isEqualTo("target");
    assertThat(missing.iterator().next().getMessage())
        .isEqualTo("target is required for action restrict_ingredient");

    assertThat(validate(doc("restrict_ingredient", "   ", null)))
        .as("blank (whitespace-only) target is rejected")
        .hasSize(1);
    assertThat(validate(doc("restrict_ingredient", "", null)))
        .as("empty target is rejected")
        .hasSize(1);
  }

  @Test
  void adjust_target_requires_non_blank_target() {
    assertThat(validate(doc("adjust_target", "protein_floor_g", null))).isEmpty();
    assertThat(validate(doc("adjust_target", null, null))).hasSize(1);
  }

  @Test
  void rebalance_macros_does_not_require_target_even_when_blank() {
    // rebalance_macros is NOT in TARGET_REQUIRED — a blank target must NOT trip the rule.
    assertThat(validate(doc("rebalance_macros", "  ", null))).isEmpty();
  }

  // ---------- staged_protocol duration rule ----------

  private DirectiveDurationDto staged(List<DirectivePhaseDto> phases) {
    return new DirectiveDurationDto("staged_protocol", phases, null);
  }

  @Test
  void non_staged_duration_skips_phase_validation_entirely() {
    // type != staged_protocol — even null/empty phases are fine.
    DirectiveDurationDto flat = new DirectiveDurationDto("single_window", null, 4);
    assertThat(validate(doc("rebalance_macros", null, flat))).isEmpty();
  }

  @Test
  void null_duration_is_allowed() {
    assertThat(validate(doc("rebalance_macros", null, null))).isEmpty();
  }

  @Test
  void staged_protocol_with_null_phases_is_rejected() {
    Set<ConstraintViolation<DirectiveInstructionDocument>> v =
        validate(doc("rebalance_macros", null, staged(null)));
    assertThat(v).hasSize(1);
    assertThat(v.iterator().next().getPropertyPath().toString()).isEqualTo("duration.phases");
    assertThat(v.iterator().next().getMessage())
        .isEqualTo("staged_protocol requires non-empty phases");
  }

  @Test
  void staged_protocol_with_empty_phases_is_rejected() {
    assertThat(validate(doc("rebalance_macros", null, staged(List.of())))).hasSize(1);
  }

  @Test
  void staged_protocol_happy_path_with_distinct_ordered_phases_passes() {
    DirectiveDurationDto d =
        staged(
            List.of(
                new DirectivePhaseDto("elimination", 2, "no dairy"),
                new DirectivePhaseDto("reintroduction", 3, "small dairy")));
    assertThat(validate(doc("eliminate_then_reintroduce", null, d))).isEmpty();
  }

  @Test
  void staged_protocol_rejects_null_phase_entry() {
    java.util.List<DirectivePhaseDto> phases = new java.util.ArrayList<>();
    phases.add(null);
    assertThat(validate(doc("rebalance_macros", null, staged(phases)))).hasSize(1);
  }

  @Test
  void staged_protocol_rejects_blank_phase_name() {
    assertThat(
            validate(
                doc(
                    "rebalance_macros",
                    null,
                    staged(List.of(new DirectivePhaseDto("  ", 2, "r"))))))
        .hasSize(1);
    assertThat(
            validate(
                doc(
                    "rebalance_macros",
                    null,
                    staged(List.of(new DirectivePhaseDto(null, 2, "r"))))))
        .hasSize(1);
  }

  @Test
  void staged_protocol_rejects_non_positive_duration_weeks() {
    Set<ConstraintViolation<DirectiveInstructionDocument>> zero =
        validate(
            doc("rebalance_macros", null, staged(List.of(new DirectivePhaseDto("p", 0, "r")))));
    assertThat(zero).hasSize(1);
    assertThat(zero.iterator().next().getMessage())
        .isEqualTo("each staged_protocol phase needs a non-blank name and positive durationWeeks");

    assertThat(
            validate(
                doc(
                    "rebalance_macros",
                    null,
                    staged(List.of(new DirectivePhaseDto("p", -1, "r"))))))
        .hasSize(1);
    assertThat(
            validate(
                doc(
                    "rebalance_macros",
                    null,
                    staged(List.of(new DirectivePhaseDto("p", null, "r"))))))
        .as("null durationWeeks is rejected")
        .hasSize(1);
  }

  @Test
  void staged_protocol_boundary_one_week_is_accepted() {
    // durationWeeks == 1 is the smallest positive value — must NOT trip the <= 0 check.
    assertThat(
            validate(
                doc("rebalance_macros", null, staged(List.of(new DirectivePhaseDto("p", 1, "r"))))))
        .isEmpty();
  }

  @Test
  void staged_protocol_rejects_consecutive_repeated_phase_names() {
    Set<ConstraintViolation<DirectiveInstructionDocument>> v =
        validate(
            doc(
                "rebalance_macros",
                null,
                staged(
                    List.of(
                        new DirectivePhaseDto("elimination", 2, "r"),
                        new DirectivePhaseDto("elimination", 3, "r")))));
    assertThat(v).hasSize(1);
    assertThat(v.iterator().next().getMessage())
        .isEqualTo("staged_protocol phases must be ordered without repeats");
  }

  @Test
  void staged_protocol_allows_same_name_when_not_consecutive() {
    // Only *consecutive* repeats are rejected (previousPhase.equals check).
    assertThat(
            validate(
                doc(
                    "rebalance_macros",
                    null,
                    staged(
                        List.of(
                            new DirectivePhaseDto("a", 1, "r"),
                            new DirectivePhaseDto("b", 1, "r"),
                            new DirectivePhaseDto("a", 1, "r"))))))
        .isEmpty();
  }
}
