package com.example.mealprep.adaptation.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint on {@link com.example.mealprep.adaptation.api.dto.PlannerHintRequest}
 * asserting that the {@code payload} JSON shape matches the {@code hintType} discriminator. Per
 * ticket §step 44.
 *
 * <ul>
 *   <li>{@code PREP_LEAD_TIME} → {@code payload.lead_time_hours} must exist and be a positive
 *       integer.
 *   <li>{@code ABSORPTION_CONFLICT} → {@code payload.blocked_by} must exist as a string ingredient
 *       mapping key.
 *   <li>Other hint types → free-form (no validation).
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PlannerHintValidator.class)
public @interface ValidPlannerHint {

  String message() default "planner-hint payload shape does not match hintType";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
