package com.example.mealprep.household.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field-level Jakarta validation marker for a custom-slot key. Asserts the key is kebab-case
 * ({@code ^[a-z0-9-]+$}), 1–48 chars, and does NOT collide with a built-in {@link
 * com.example.mealprep.household.domain.entity.SlotKind} name (breakfast / lunch / dinner / snack /
 * custom) — per LLD §Validation (lines 369-374). Applied to {@code CustomSlotDefinition.key}.
 *
 * <p>A {@code null} key is left to {@code @NotNull}/{@code @NotBlank} (the OpenAPI schema marks
 * {@code key} required); this validator only ranges over the format + collision rules so a
 * contract-valid kebab-case key that happens to be {@code "dinner"} is still rejected (it would
 * shadow the built-in slot kind in the planner's slot composition).
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SlotKeyValidator.class)
public @interface ValidSlotKey {

  /** Maximum permitted key length. */
  int MAX_LENGTH = 48;

  String message() default
      "slot key must be kebab-case, 1-48 chars, and not collide with a built-in slot kind";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
