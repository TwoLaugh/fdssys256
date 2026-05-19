package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.provisions.api.dto.FreezerExtensionDto;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.validation.StorageLocationValidatable;
import com.example.mealprep.provisions.validation.ValidQuantityValidator;
import com.example.mealprep.provisions.validation.ValidStorageLocationValidator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests that invoke {@link ValidStorageLocationValidator} and {@link
 * ValidQuantityValidator} directly (real validator instances, no Spring/Hibernate-Validator
 * factory). Each case pins one branch / boundary so the surviving conditional, boundary and
 * return-value mutants are killed:
 *
 * <ul>
 *   <li>storage: null-skip, location/mode null-skip, SPICE_RACK↔STATUS, others↔QUANTITY,
 *       FREEZER↔freezerExtension biconditional (all four corners).
 *   <li>quantity: null-skip, negative reject, scale boundary (3 ok / 4 reject), magnitude boundary
 *       (1,000,000 ok / above reject).
 * </ul>
 *
 * The {@code ConstraintValidatorContext} is unused by both validators, so {@code null} is passed.
 */
class ProvisionsConstraintValidatorTest {

  private record StubItem(
      StorageLocation storageLocation,
      TrackingMode trackingMode,
      FreezerExtensionDto freezerExtension)
      implements StorageLocationValidatable {}

  private static FreezerExtensionDto freezerExt() {
    return new FreezerExtensionDto(null, null, null, null, null);
  }

  private final ValidStorageLocationValidator storage = new ValidStorageLocationValidator();
  private final ValidQuantityValidator quantity = new ValidQuantityValidator();

  // ---------------- @ValidStorageLocation ----------------

  @Test
  void storage_nullValue_isValid() {
    assertThat(storage.isValid(null, null)).isTrue();
  }

  @Test
  void storage_nullLocation_isValid_notDoubleReported() {
    assertThat(storage.isValid(new StubItem(null, TrackingMode.QUANTITY, null), null)).isTrue();
  }

  @Test
  void storage_nullMode_isValid_notDoubleReported() {
    assertThat(storage.isValid(new StubItem(StorageLocation.FRIDGE, null, null), null)).isTrue();
  }

  @Test
  void storage_spiceRack_requiresStatus_statusOk() {
    assertThat(
            storage.isValid(
                new StubItem(StorageLocation.SPICE_RACK, TrackingMode.STATUS, null), null))
        .isTrue();
  }

  @Test
  void storage_spiceRack_withQuantity_fails() {
    assertThat(
            storage.isValid(
                new StubItem(StorageLocation.SPICE_RACK, TrackingMode.QUANTITY, null), null))
        .isFalse();
  }

  @Test
  void storage_fridge_requiresQuantity_quantityOk() {
    assertThat(
            storage.isValid(
                new StubItem(StorageLocation.FRIDGE, TrackingMode.QUANTITY, null), null))
        .isTrue();
  }

  @Test
  void storage_fridge_withStatus_fails() {
    assertThat(
            storage.isValid(new StubItem(StorageLocation.FRIDGE, TrackingMode.STATUS, null), null))
        .isFalse();
  }

  @Test
  void storage_cupboard_requiresQuantity_quantityOk() {
    assertThat(
            storage.isValid(
                new StubItem(StorageLocation.CUPBOARD, TrackingMode.QUANTITY, null), null))
        .isTrue();
  }

  @Test
  void storage_freezer_withExtension_andQuantity_isValid() {
    assertThat(
            storage.isValid(
                new StubItem(StorageLocation.FREEZER, TrackingMode.QUANTITY, freezerExt()), null))
        .isTrue();
  }

  @Test
  void storage_freezer_withoutExtension_fails() {
    assertThat(
            storage.isValid(
                new StubItem(StorageLocation.FREEZER, TrackingMode.QUANTITY, null), null))
        .isFalse();
  }

  @Test
  void storage_nonFreezer_withExtension_fails() {
    assertThat(
            storage.isValid(
                new StubItem(StorageLocation.FRIDGE, TrackingMode.QUANTITY, freezerExt()), null))
        .isFalse();
  }

  @Test
  void storage_nonFreezer_withoutExtension_isValid() {
    assertThat(
            storage.isValid(
                new StubItem(StorageLocation.FRIDGE, TrackingMode.QUANTITY, null), null))
        .isTrue();
  }

  // ---------------- @ValidQuantity ----------------

  @Test
  void quantity_null_isValid() {
    assertThat(quantity.isValid(null, null)).isTrue();
  }

  @Test
  void quantity_zero_isValid() {
    assertThat(quantity.isValid(BigDecimal.ZERO, null)).isTrue();
  }

  @Test
  void quantity_negative_fails() {
    assertThat(quantity.isValid(new BigDecimal("-0.001"), null)).isFalse();
  }

  @Test
  void quantity_scaleThree_isValid_boundary() {
    assertThat(quantity.isValid(new BigDecimal("1.234"), null)).isTrue();
  }

  @Test
  void quantity_scaleFour_fails_boundary() {
    assertThat(quantity.isValid(new BigDecimal("1.2345"), null)).isFalse();
  }

  @Test
  void quantity_exactlyMaxMagnitude_isValid_boundary() {
    assertThat(quantity.isValid(new BigDecimal("1000000"), null)).isTrue();
  }

  @Test
  void quantity_aboveMaxMagnitude_fails_boundary() {
    assertThat(quantity.isValid(new BigDecimal("1000000.001"), null)).isFalse();
  }

  @Test
  void quantity_typicalPositive_isValid() {
    assertThat(quantity.isValid(new BigDecimal("250.5"), null)).isTrue();
  }
}
