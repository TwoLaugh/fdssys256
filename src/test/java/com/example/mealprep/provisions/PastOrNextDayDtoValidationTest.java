package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.api.dto.GroceryOrderLine;
import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import com.example.mealprep.provisions.api.dto.UpsertSupplierProductRequest;
import com.example.mealprep.provisions.api.dto.WasteReason;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * DTO-level tests that drive {@code @PastOrNextDay} through a real {@link Validator} wired via
 * Spring's {@link LocalValidatorFactoryBean} — so {@code PastOrNextDayValidator} is created by the
 * {@code SpringConstraintValidatorFactory} with the constructor-injected {@link Clock} (the prod
 * path). The clock is fixed to 2026-05-25, making "server-today" deterministic.
 *
 * <p>For each of the three affected DTOs: {@code today + 1} (the skew band) PASSES and {@code today
 * + 2} still FAILS on the date field, with no other violations leaking in.
 */
class PastOrNextDayDtoValidationTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 5, 25);
  private static final LocalDate TODAY_PLUS_1 = TODAY.plusDays(1); // 26th — must pass
  private static final LocalDate TODAY_PLUS_2 = TODAY.plusDays(2); // 27th — must fail

  private static AnnotationConfigApplicationContext context;
  private static Validator validator;

  @Configuration
  static class FixedClockValidationConfig {
    @Bean
    Clock systemClock() {
      return Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    @Bean
    LocalValidatorFactoryBean validatorFactory() {
      return new LocalValidatorFactoryBean();
    }
  }

  @BeforeAll
  static void setUp() {
    context = new AnnotationConfigApplicationContext(FixedClockValidationConfig.class);
    validator = context.getBean(Validator.class);
  }

  @AfterAll
  static void tearDown() {
    context.close();
  }

  // ---------------- LogWasteRequest.occurredOn ----------------

  private static LogWasteRequest waste(LocalDate occurredOn) {
    return new LogWasteRequest(
        null, "celery", null, null, WasteReason.EXPIRED, null, occurredOn, null);
  }

  @Test
  void logWaste_occurredOnTodayPlus1_passes() {
    assertThat(occurredOnViolations(waste(TODAY_PLUS_1))).isEmpty();
  }

  @Test
  void logWaste_occurredOnTodayPlus2_fails() {
    assertThat(occurredOnViolations(waste(TODAY_PLUS_2))).isNotEmpty();
  }

  private List<String> occurredOnViolations(LogWasteRequest req) {
    return validator.validate(req).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .filter("occurredOn"::equals)
        .toList();
  }

  // ---------------- UpsertSupplierProductRequest.lastChecked ----------------

  private static UpsertSupplierProductRequest supplierProduct(LocalDate lastChecked) {
    return new UpsertSupplierProductRequest(
        "PROD-1",
        "tesco",
        "Cheddar 200g",
        new BigDecimal("2.50"),
        new BigDecimal("0.0125"),
        "g",
        null,
        null,
        null,
        null,
        lastChecked,
        null);
  }

  @Test
  void supplierProduct_lastCheckedTodayPlus1_passes() {
    assertThat(lastCheckedViolations(supplierProduct(TODAY_PLUS_1))).isEmpty();
  }

  @Test
  void supplierProduct_lastCheckedTodayPlus2_fails() {
    assertThat(lastCheckedViolations(supplierProduct(TODAY_PLUS_2))).isNotEmpty();
  }

  private List<String> lastCheckedViolations(UpsertSupplierProductRequest req) {
    return validator.validate(req).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .filter("lastChecked"::equals)
        .toList();
  }

  // ---------------- GroceryOrderImportCommand.deliveredOn ----------------

  private static GroceryOrderImportCommand groceryImport(LocalDate deliveredOn) {
    GroceryOrderLine line =
        new GroceryOrderLine(
            "PROD-1", "Cheddar 200g", null, new BigDecimal("1"), "ea", null, null, null);
    return new GroceryOrderImportCommand(
        "tesco", "ORDER-1", deliveredOn, List.of(line), null, null);
  }

  @Test
  void groceryImport_deliveredOnTodayPlus1_passes() {
    assertThat(deliveredOnViolations(groceryImport(TODAY_PLUS_1))).isEmpty();
  }

  @Test
  void groceryImport_deliveredOnTodayPlus2_fails() {
    assertThat(deliveredOnViolations(groceryImport(TODAY_PLUS_2))).isNotEmpty();
  }

  private List<String> deliveredOnViolations(GroceryOrderImportCommand req) {
    return validator.validate(req).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .filter("deliveredOn"::equals)
        .toList();
  }
}
