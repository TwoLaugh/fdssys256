package com.example.mealprep.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Basic password constraint for 01a: length and whitespace only. Username-equality and
 * breached-list checks live in {@code PasswordStrengthValidator} (service-side); annotation can
 * only see the password field, not the surrounding record.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidPasswordValidator.class)
public @interface ValidPassword {

  String message() default "must be 12–128 chars with no leading or trailing whitespace";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
