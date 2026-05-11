package com.example.mealprep.nutrition.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level Bean Validation constraint for {@link
 * com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument} per LLD line 869. Runs the
 * deterministic schema gate:
 *
 * <ul>
 *   <li>{@code action} must be a known verb ({@code restrict_ingredient}, {@code adjust_target},
 *       {@code rebalance_macros}, {@code eliminate_then_reintroduce}, {@code
 *       downgrade_sensitivity}).
 *   <li>{@code target} must be non-blank for {@code restrict_ingredient} / {@code adjust_target}.
 *   <li>{@code duration.type == "staged_protocol"} requires ordered non-overlapping phases with a
 *       positive total week count.
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DirectiveInstructionValidator.class)
public @interface ValidDirectiveInstruction {
  String message() default "invalid directive instruction document";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
