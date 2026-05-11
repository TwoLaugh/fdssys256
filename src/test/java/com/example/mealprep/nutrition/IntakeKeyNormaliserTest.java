package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.domain.service.internal.IntakeKeyNormaliser;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IntakeKeyNormaliser}. Pure stateless logic — no Spring context. */
class IntakeKeyNormaliserTest {

  private final IntakeKeyNormaliser normaliser = new IntakeKeyNormaliser();

  @Test
  void lowercases_and_trims() {
    assertThat(normaliser.normalise("  Chicken Breast  ")).isEqualTo("chicken breast");
  }

  @Test
  void collapses_internal_whitespace() {
    assertThat(normaliser.normalise("  Chicken   Breast  ")).isEqualTo("chicken breast");
    assertThat(normaliser.normalise("a\t\tb\n\nc")).isEqualTo("a b c");
  }

  @Test
  void is_idempotent() {
    String once = normaliser.normalise("  Chicken   Breast  ");
    assertThat(normaliser.normalise(once)).isEqualTo(once);
  }

  @Test
  void null_returns_null() {
    assertThat(normaliser.normalise(null)).isNull();
  }

  @Test
  void empty_and_whitespace_only_returns_empty() {
    assertThat(normaliser.normalise("")).isEqualTo("");
    assertThat(normaliser.normalise("   \t\n  ")).isEqualTo("");
  }
}
