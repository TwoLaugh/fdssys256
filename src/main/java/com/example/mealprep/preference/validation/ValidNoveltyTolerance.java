package com.example.mealprep.preference.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level Jakarta validation marker applied to {@code
 * LifestyleConfigDocument.NoveltyTolerance}. Asserts:
 *
 * <ul>
 *   <li>each per-slot {@code mode} is one of {@code rotation | batch_repeat | high_variety |
 *       static};
 *   <li>{@code rotation} requires {@code rotationSize > 0};
 *   <li>{@code batch_repeat} requires {@code maxConsecutiveSame > 0};
 *   <li>{@code high_variety} requires {@code newPerWeek > 0};
 *   <li>{@code static} mode accepts no mode-specific fields (rotationSize / maxConsecutiveSame /
 *       newPerWeek must be null);
 *   <li>all {@code recipeRepeatCooldownWeeks} values are &gt;= 0;
 *   <li>{@code ingredientFrequencyCaps} keys are non-blank.
 * </ul>
 *
 * <p>Per ticket §20 and {@code lld/preference.md:653}.
 */
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoveltyToleranceValidator.class)
public @interface ValidNoveltyTolerance {

  String message() default "novelty tolerance invalid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
