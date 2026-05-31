package com.example.mealprep.household.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field-level Jakarta validation marker for a per-slot headcount. A {@code null} value is accepted
 * (the field is optional — it falls back to {@code defaultHeadcount} / 1 at resolve time); a
 * non-null value must be between {@link #MIN} and {@link #MAX} inclusive, matching the planner's
 * per-eater sanity check (LLD §Validation lines 369-374). Applied to {@code SlotDefault.headcount},
 * {@code CustomSlotDefinition.headcount}, and {@code HouseholdSettingsDocument.defaultHeadcount}.
 *
 * <p>Bounds are deliberately identical to the OpenAPI {@code headcount} schema (minimum 1, maximum
 * 16) so a request that passes the swagger-request-validator also passes this bean-validation
 * constraint — the validator adds enforcement on the in-process service path (where there is no
 * OpenAPI gate) without rejecting any contract-valid input.
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = HeadcountValidator.class)
public @interface ValidHeadcount {

  /** Minimum permitted headcount (inclusive). */
  int MIN = 1;

  /** Maximum permitted headcount (inclusive). */
  int MAX = 16;

  String message() default "headcount must be between 1 and 16";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
