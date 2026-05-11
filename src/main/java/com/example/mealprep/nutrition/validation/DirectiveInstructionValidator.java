package com.example.mealprep.nutrition.validation;

import com.example.mealprep.nutrition.api.dto.DirectiveDurationDto;
import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectivePhaseDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

/**
 * Schema gate for {@link DirectiveInstructionDocument}. Pure deterministic — same input twice →
 * same outcome. Returns {@code false} (Bean Validation will surface as 400) when any rule fails;
 * the violation message identifies which rule fired so callers can debug.
 */
public class DirectiveInstructionValidator
    implements ConstraintValidator<ValidDirectiveInstruction, DirectiveInstructionDocument> {

  private static final Set<String> KNOWN_ACTIONS =
      Set.of(
          "restrict_ingredient",
          "adjust_target",
          "rebalance_macros",
          "eliminate_then_reintroduce",
          "downgrade_sensitivity");

  private static final Set<String> TARGET_REQUIRED = Set.of("restrict_ingredient", "adjust_target");

  @Override
  public boolean isValid(DirectiveInstructionDocument value, ConstraintValidatorContext context) {
    if (value == null) {
      // @NotNull at the field handles null. We pass null through here.
      return true;
    }
    String action = value.action();
    if (action == null || !KNOWN_ACTIONS.contains(action)) {
      addViolation(context, "action must be one of " + KNOWN_ACTIONS, "action");
      return false;
    }
    if (TARGET_REQUIRED.contains(action) && (value.target() == null || value.target().isBlank())) {
      addViolation(context, "target is required for action " + action, "target");
      return false;
    }
    DirectiveDurationDto duration = value.duration();
    if (duration != null
        && "staged_protocol".equals(duration.type())
        && !validStagedProtocol(duration, context)) {
      return false;
    }
    return true;
  }

  private static boolean validStagedProtocol(
      DirectiveDurationDto duration, ConstraintValidatorContext context) {
    if (duration.phases() == null || duration.phases().isEmpty()) {
      addViolation(context, "staged_protocol requires non-empty phases", "duration.phases");
      return false;
    }
    int totalWeeks = 0;
    String previousPhase = null;
    for (DirectivePhaseDto phase : duration.phases()) {
      if (phase == null
          || phase.phase() == null
          || phase.phase().isBlank()
          || phase.durationWeeks() == null
          || phase.durationWeeks() <= 0) {
        addViolation(
            context,
            "each staged_protocol phase needs a non-blank name and positive durationWeeks",
            "duration.phases");
        return false;
      }
      if (previousPhase != null && previousPhase.equals(phase.phase())) {
        addViolation(
            context, "staged_protocol phases must be ordered without repeats", "duration.phases");
        return false;
      }
      previousPhase = phase.phase();
      totalWeeks += phase.durationWeeks();
    }
    if (totalWeeks <= 0) {
      addViolation(context, "staged_protocol phase weeks must sum to > 0", "duration.phases");
      return false;
    }
    return true;
  }

  private static void addViolation(
      ConstraintValidatorContext context, String message, String propertyPath) {
    context.disableDefaultConstraintViolation();
    context
        .buildConstraintViolationWithTemplate(message)
        .addPropertyNode(propertyPath)
        .addConstraintViolation();
  }
}
