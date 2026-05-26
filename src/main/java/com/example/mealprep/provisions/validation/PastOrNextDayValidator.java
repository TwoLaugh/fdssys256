package com.example.mealprep.provisions.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Implementation of {@link PastOrNextDay}: a value is valid when it is not after {@code
 * LocalDate.now(clock) + 1 day}.
 *
 * <p>The {@link Clock} is resolved two ways:
 *
 * <ul>
 *   <li>When created by Spring's {@code SpringConstraintValidatorFactory} (the default for {@code
 *       LocalValidatorFactoryBean}), the shared {@code Clock} bean (see {@code
 *       AuthSecurityConfig#systemClock()}) is constructor-injected — tests can swap a fixed clock.
 *   <li>When instantiated outside Spring (e.g. a raw {@code
 *       Validation.buildDefaultValidatorFactory} in a unit test), the no-arg constructor falls back
 *       to {@link Clock#systemUTC()}.
 * </ul>
 */
public class PastOrNextDayValidator implements ConstraintValidator<PastOrNextDay, LocalDate> {

  private final Clock clock;

  /** Fallback used when instantiated outside Spring (raw {@code Validator} in a unit test). */
  public PastOrNextDayValidator() {
    this(Clock.systemUTC());
  }

  /**
   * Used by Spring's {@code SpringConstraintValidatorFactory} to inject the shared clock.
   * {@code @Autowired} marks this as the constructor to use when the validator is created by
   * Spring; with two constructors and no marker Spring would fall back to the no-arg one
   * (systemUTC).
   */
  @Autowired
  public PastOrNextDayValidator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
    if (value == null) {
      // @NotNull owns presence — same contract as @PastOrPresent; we don't double-report.
      return true;
    }
    return !value.isAfter(LocalDate.now(clock).plusDays(1));
  }
}
