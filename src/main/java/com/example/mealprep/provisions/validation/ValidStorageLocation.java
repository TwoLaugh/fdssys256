package com.example.mealprep.provisions.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level validation enforcing the storage-location ↔ tracking-mode pairing and the {@code
 * freezerExtension non-null iff storageLocation == FREEZER} rule. Applied to {@code
 * CreateInventoryItemRequest} / {@code UpdateInventoryItemRequest}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidStorageLocationValidator.class)
public @interface ValidStorageLocation {

  String message() default "invalid combination of storageLocation, trackingMode, freezerExtension";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
