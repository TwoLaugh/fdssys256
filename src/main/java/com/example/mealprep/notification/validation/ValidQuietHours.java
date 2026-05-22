package com.example.mealprep.notification.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint on the notification-preference update request. Asserts the quiet-hours
 * fields are internally consistent and the timezone is a valid {@link java.time.ZoneId}:
 *
 * <ul>
 *   <li>{@code quietHoursStart} and {@code quietHoursEnd} are both null (and {@code
 *       quietHoursEnabled = false}) OR both non-null (and {@code quietHoursEnabled = true});
 *   <li>{@code start == end} (zero-length window) is rejected;
 *   <li>{@code timezone} parses via {@code ZoneId.of(...)}.
 * </ul>
 *
 * Wrap-around windows (e.g. 22:00 → 06:00) are permitted and handled by the evaluator.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = QuietHoursValidator.class)
public @interface ValidQuietHours {

  String message() default "invalid quiet-hours configuration or timezone";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
