package com.example.mealprep.provisions.validation;

import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementation of {@link ValidStorageLocation}.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>{@code SPICE_RACK} requires {@code STATUS} tracking; the others require {@code QUANTITY}.
 *   <li>{@code FREEZER} requires a non-null {@code freezerExtension}; non-{@code FREEZER} requires
 *       it to be null.
 * </ul>
 *
 * <p>Returns {@code true} if any of {@code storageLocation} or {@code trackingMode} is null — those
 * are caught by {@code @NotNull} elsewhere. We don't double-report.
 */
public class ValidStorageLocationValidator
    implements ConstraintValidator<ValidStorageLocation, StorageLocationValidatable> {

  @Override
  public boolean isValid(StorageLocationValidatable value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    StorageLocation location = value.storageLocation();
    TrackingMode mode = value.trackingMode();
    if (location == null || mode == null) {
      return true;
    }

    boolean modeOk;
    if (location == StorageLocation.SPICE_RACK) {
      modeOk = mode == TrackingMode.STATUS;
    } else {
      modeOk = mode == TrackingMode.QUANTITY;
    }
    if (!modeOk) {
      return false;
    }

    boolean freezerOk = (location == StorageLocation.FREEZER) == (value.freezerExtension() != null);
    return freezerOk;
  }
}
