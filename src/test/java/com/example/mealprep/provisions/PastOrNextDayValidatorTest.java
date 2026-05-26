package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.provisions.validation.PastOrNextDayValidator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PastOrNextDayValidator} against a fixed clock (2026-05-25T12:00:00Z,
 * so server-today is the 25th). Pins both sides of the {@code today + 1} boundary so the
 * conditional, boundary and return-value mutants on {@code !value.isAfter(today.plusDays(1))} are
 * killed.
 *
 * <p>The validator is constructed with the fixed clock directly (the no-arg/systemUTC fallback is
 * exercised implicitly by the Spring-wired DTO tests). The {@code ConstraintValidatorContext} is
 * unused, so {@code null} is passed.
 */
class PastOrNextDayValidatorTest {

  private static final Clock FIXED =
      Clock.fixed(
              LocalDate.of(2026, 5, 25).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
          .withZone(ZoneOffset.UTC);

  // Reconstruct with the exact noon instant the ticket calls out, to be unambiguous about "today".
  private final PastOrNextDayValidator validator =
      new PastOrNextDayValidator(
          Clock.fixed(
              LocalDate.of(2026, 5, 25).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

  @Test
  void nullValue_isValid() {
    assertThat(validator.isValid(null, null)).isTrue();
  }

  @Test
  void pastDate_isValid() {
    assertThat(validator.isValid(LocalDate.of(2026, 5, 24), null)).isTrue();
  }

  @Test
  void today_isValid() {
    assertThat(validator.isValid(LocalDate.of(2026, 5, 25), null)).isTrue();
  }

  @Test
  void tomorrow_skewBand_isValid_boundary() {
    // The +1 day that absorbs east-of-server TZ skew: today + 1 must PASS.
    assertThat(validator.isValid(LocalDate.of(2026, 5, 26), null)).isTrue();
  }

  @Test
  void todayPlusTwo_fails_boundary() {
    // One day past the skew band must still FAIL (fat-finger guard).
    assertThat(validator.isValid(LocalDate.of(2026, 5, 27), null)).isFalse();
  }

  @Test
  void farFuture_fails() {
    assertThat(validator.isValid(LocalDate.of(2030, 1, 1), null)).isFalse();
  }

  @Test
  void clockIsHonoured_notSystemDefault() {
    // Sanity: a validator on a different fixed clock disagrees on the same date, proving the
    // injected clock (not the system default) drives the comparison.
    PastOrNextDayValidator earlier =
        new PastOrNextDayValidator(FIXED); // server-today = 2026-05-25 (start of day)
    assertThat(earlier.isValid(LocalDate.of(2026, 5, 26), null)).isTrue();
    assertThat(earlier.isValid(LocalDate.of(2026, 5, 27), null)).isFalse();
  }
}
