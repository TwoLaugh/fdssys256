package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import com.example.mealprep.provisions.api.dto.WasteListQuery;
import com.example.mealprep.provisions.api.dto.WasteReason;
import com.example.mealprep.provisions.domain.entity.WasteEntry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * Pure unit tests for the class-level waste validators and the append-only entity invariant. No
 * Spring context, no DB — checks only the constraint annotations and the entity's reflective shape
 * (LLD line 258: no {@code @Version}, no {@code @LastModifiedDate}).
 *
 * <p>Cross-resource "quantity ≤ inventory" rule is service-side; see {@code WasteLoggingIT}
 * 422-path.
 */
class WasteValidatorTest {

  private static final Validator VALIDATOR;

  static {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      VALIDATOR = factory.getValidator();
    }
  }

  // ---------------- @ValidWasteQuantity ----------------

  @Test
  void validRequest_freeForm_withoutQuantity_passes() {
    LogWasteRequest req =
        new LogWasteRequest(
            null,
            "celery",
            null,
            null,
            WasteReason.EXPIRED,
            null,
            LocalDate.parse("2026-05-08"),
            null);
    Set<ConstraintViolation<LogWasteRequest>> violations = VALIDATOR.validate(req);
    assertThat(violations).isEmpty();
  }

  @Test
  void validRequest_linked_withQuantityAndUnit_passes() {
    LogWasteRequest req =
        new LogWasteRequest(
            UUID.randomUUID(),
            "cheese",
            new BigDecimal("100.000"),
            "g",
            WasteReason.EXPIRED,
            null,
            LocalDate.parse("2026-05-08"),
            null);
    Set<ConstraintViolation<LogWasteRequest>> violations = VALIDATOR.validate(req);
    assertThat(violations).isEmpty();
  }

  @Test
  void invalid_quantityWithoutUnit_fails() {
    LogWasteRequest req =
        new LogWasteRequest(
            UUID.randomUUID(),
            "cheese",
            new BigDecimal("100.000"),
            null,
            WasteReason.EXPIRED,
            null,
            LocalDate.parse("2026-05-08"),
            null);
    Set<ConstraintViolation<LogWasteRequest>> violations = VALIDATOR.validate(req);
    assertThat(violations).isNotEmpty();
  }

  @Test
  void invalid_blankItemName_fails() {
    LogWasteRequest req =
        new LogWasteRequest(
            null, "", null, null, WasteReason.EXPIRED, null, LocalDate.parse("2026-05-08"), null);
    Set<ConstraintViolation<LogWasteRequest>> violations = VALIDATOR.validate(req);
    assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("itemName"));
  }

  @Test
  void invalid_reasonNull_fails() {
    LogWasteRequest req =
        new LogWasteRequest(
            null, "celery", null, null, null, null, LocalDate.parse("2026-05-08"), null);
    Set<ConstraintViolation<LogWasteRequest>> violations = VALIDATOR.validate(req);
    assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("reason"));
  }

  @Test
  void invalid_occurredOnInFuture_fails() {
    LogWasteRequest req =
        new LogWasteRequest(
            null,
            "celery",
            null,
            null,
            WasteReason.EXPIRED,
            null,
            LocalDate.now().plusDays(1),
            null);
    Set<ConstraintViolation<LogWasteRequest>> violations = VALIDATOR.validate(req);
    assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("occurredOn"));
  }

  // ---------------- @ValidWasteDateRange ----------------

  @Test
  void validDateRange_fromBeforeTo_passes() {
    WasteListQuery query =
        new WasteListQuery(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-05-01"));
    Set<ConstraintViolation<WasteListQuery>> violations = VALIDATOR.validate(query);
    assertThat(violations).isEmpty();
  }

  @Test
  void validDateRange_fromEqualsTo_passes() {
    WasteListQuery query =
        new WasteListQuery(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-01"));
    Set<ConstraintViolation<WasteListQuery>> violations = VALIDATOR.validate(query);
    assertThat(violations).isEmpty();
  }

  @Test
  void invalidDateRange_fromAfterTo_fails() {
    WasteListQuery query =
        new WasteListQuery(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-04-01"));
    Set<ConstraintViolation<WasteListQuery>> violations = VALIDATOR.validate(query);
    assertThat(violations).isNotEmpty();
  }

  @Test
  void dateRange_nullBound_passes() {
    // Class-level validator skips when bounds are null — handled by @NotNull on the controller
    // method signature when the endpoint demands them. We don't double-report.
    WasteListQuery openEnd = new WasteListQuery(null, LocalDate.parse("2026-04-01"));
    WasteListQuery openStart = new WasteListQuery(LocalDate.parse("2026-04-01"), null);
    assertThat(VALIDATOR.validate(openEnd)).isEmpty();
    assertThat(VALIDATOR.validate(openStart)).isEmpty();
  }

  // ---------------- Append-only entity invariant ----------------

  @Test
  void wasteEntry_hasNoVersionField() {
    boolean hasVersion = false;
    for (Field f : WasteEntry.class.getDeclaredFields()) {
      if (f.isAnnotationPresent(jakarta.persistence.Version.class)) {
        hasVersion = true;
        break;
      }
    }
    assertThat(hasVersion).as("WasteEntry must be append-only — no @Version").isFalse();
  }

  @Test
  void wasteEntry_hasNoLastModifiedDateField() {
    boolean hasLastModified = false;
    for (Field f : WasteEntry.class.getDeclaredFields()) {
      if (f.isAnnotationPresent(LastModifiedDate.class)) {
        hasLastModified = true;
        break;
      }
    }
    assertThat(hasLastModified)
        .as("WasteEntry must be append-only — no @LastModifiedDate")
        .isFalse();
  }
}
