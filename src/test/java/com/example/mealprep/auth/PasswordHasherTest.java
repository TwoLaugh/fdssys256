package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.service.internal.PasswordHasher;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

  private final AuthProperties properties =
      new AuthProperties(12, null, null, null, null, null, null, null, null);
  private final PasswordHasher hasher = new PasswordHasher(properties);

  @Test
  void cost_returnsTheConfiguredFactor() {
    assertThat(hasher.cost()).isEqualTo(12);
  }

  @Test
  void hash_thenVerify_returnsTrueForMatchingPassword() {
    String hashed = hasher.hash("correct-horse-battery");

    assertThat(hashed).startsWith("$2");
    assertThat(hasher.verify("correct-horse-battery", hashed)).isTrue();
  }

  @Test
  void hash_isNonDeterministic_acrossCalls() {
    // BCrypt salts every output. Two hashes of the same plaintext must differ.
    String a = hasher.hash("correct-horse-battery");
    String b = hasher.hash("correct-horse-battery");

    assertThat(a).isNotEqualTo(b);
    assertThat(hasher.verify("correct-horse-battery", a)).isTrue();
    assertThat(hasher.verify("correct-horse-battery", b)).isTrue();
  }

  @Test
  void verify_returnsFalse_forWrongPassword() {
    String hashed = hasher.hash("correct-horse-battery");

    assertThat(hasher.verify("wrong-password-attempt", hashed)).isFalse();
  }

  @Test
  void verify_returnsFalse_forNullInputs() {
    String hashed = hasher.hash("correct-horse-battery");

    assertThat(hasher.verify(null, hashed)).isFalse();
    assertThat(hasher.verify("correct-horse-battery", null)).isFalse();
  }

  @Test
  void hash_rejectsNullInput() {
    assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cost12Hash_encodesCostFactorInOutput() {
    // BCrypt's output `$2a$<cost>$...` lets us read the cost back out without timing.
    String hashed = hasher.hash("correct-horse-battery");

    assertThat(hashed).contains("$12$");
  }

  @Test
  void cost12_takesNonTrivialWallClockTime() {
    // Sanity rather than a strict guard: cost-12 BCrypt should not complete instantly. Generous
    // floor so this passes on slow CI but still catches an accidental cost=4 misconfiguration.
    long start = System.nanoTime();
    hasher.hash("correct-horse-battery");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isGreaterThan(50);
  }
}
