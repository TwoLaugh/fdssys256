package com.example.mealprep.provisions.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint for client-supplied calendar dates: accepts any value up to and including {@code
 * server-today + 1 day}.
 *
 * <p>Rationale (provisions/02a): a bare {@code @PastOrPresent} resolves "today" in the server's
 * default zone. A client EAST of the server (server runs UTC) submitting a "today"-dated entry in
 * the window after THEIR local midnight has a date that looks like the server's "tomorrow", drawing
 * a spurious 400. No client on Earth is more than one calendar day ahead of any server, so allowing
 * {@code today + 1} fully absorbs the skew while still rejecting fat-finger far-future dates.
 *
 * <p>Like {@code @PastOrPresent}, this constraint treats {@code null} as valid — presence is owned
 * by a separate {@code @NotNull}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PastOrNextDayValidator.class)
public @interface PastOrNextDay {

  String message() default "must not be a future date";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
