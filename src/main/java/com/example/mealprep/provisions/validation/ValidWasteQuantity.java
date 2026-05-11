package com.example.mealprep.provisions.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level validation enforcing the shape rules between {@code inventoryItemId}, {@code
 * quantity}, and {@code unit} on a waste-log request (LLD line 548 — shape only).
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>If {@code quantity != null} then {@code unit != null} (consistency).
 *   <li>If {@code inventoryItemId == null} then {@code quantity} MAY be null (free-form
 *       pre-tracking waste is permitted).
 * </ul>
 *
 * <p>The cross-resource "waste quantity ≤ inventory.quantity" rule is service-side (LLD line 550)
 * because it requires a DB lookup; the validator stays request-scoped.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = WasteQuantityValidator.class)
public @interface ValidWasteQuantity {

  String message() default
      "quantity requires unit (and is permitted to be null only when" + " inventoryItemId is null)";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
