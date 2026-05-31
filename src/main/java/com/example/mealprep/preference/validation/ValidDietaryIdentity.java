package com.example.mealprep.preference.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level Jakarta validation marker for a user's dietary identity. Two targets are supported
 * via two validator implementations:
 *
 * <ul>
 *   <li>On {@link com.example.mealprep.preference.api.dto.DietaryIdentityDto} ({@link
 *       DietaryIdentityValidator}): asserts {@code base} is a known dietary base, each {@code
 *       exception.allows} is a known sub-category (or a conditional "X-free" qualifier), and each
 *       {@code exception.context} is one of {@code any | social | weekend | weekday}.
 *   <li>On {@link com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest} ({@link
 *       DietaryIdentityRequestValidator}): all of the above PLUS the safety collision check — no
 *       {@code exception.allows} may name a substance the user has listed as an allergy or a hard
 *       intolerance. Letting an exception silently re-admit an allergen would defeat the
 *       safety-critical hard filter, so the LLD frames this as a guard.
 * </ul>
 *
 * <p>Per {@code lld/preference.md} §Validation (the {@code @ValidDietaryIdentity} bullet) and
 * finding preference-4. Each rule emits a separate violation so the client sees every failing entry
 * in one round trip, matching the {@link ValidNoveltyTolerance} pattern.
 */
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {DietaryIdentityValidator.class, DietaryIdentityRequestValidator.class})
public @interface ValidDietaryIdentity {

  String message() default "dietary identity invalid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
