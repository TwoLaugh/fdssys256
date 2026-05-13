package com.example.mealprep.discovery.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level Jakarta validation marker applied to {@code DiscoveryConstraints} document inputs.
 * Asserts schemaVersion is supported, requiredMealTypes are members of the canonical set, no
 * negative time bounds, and mustExcludeIngredientMappingKeys entries are pre-normalised (lowercase,
 * whitespace-trimmed). Per ticket invariant 28 and LLD line 465.
 *
 * <p>The cross-record {@code maxRecipesPerSource <= requestedCount} check lives on {@code
 * StartDiscoveryJobRequest} itself via {@code @AssertTrue} — the validator below only sees the
 * inner record, not the parent.
 */
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DiscoveryConstraintsValidator.class)
public @interface ValidDiscoveryConstraints {

  String message() default "discovery constraints invalid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
