package com.example.mealprep.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Username constraint: 3–32 chars, ASCII alphanumerics plus underscore and hyphen.
 *
 * <p>The 32-char ceiling is the OpenAPI-spec ceiling; the migration column is wider for headroom.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidUsernameValidator.class)
public @interface ValidUsername {

  String message() default "must be 3–32 chars of letters, digits, underscore or hyphen";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
