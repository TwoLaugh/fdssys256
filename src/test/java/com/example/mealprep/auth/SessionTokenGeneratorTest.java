package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.auth.domain.service.internal.SessionTokenGenerator;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionTokenGeneratorTest {

  private final SessionTokenGenerator generator = new SessionTokenGenerator();

  @Test
  void generateRawToken_decodesTo32Bytes_giving256BitsOfEntropy() {
    String token = generator.generateRawToken();

    byte[] decoded = Base64.getUrlDecoder().decode(token);
    assertThat(decoded).hasSize(32);
  }

  @Test
  void generateRawToken_isUrlSafe_andUnpadded() {
    String token = generator.generateRawToken();

    assertThat(token).doesNotContain("=", "+", "/");
  }

  @Test
  void generateRawToken_hasNoCollisions_over1000Generations() {
    Set<String> tokens = new HashSet<>(1000);
    for (int i = 0; i < 1000; i++) {
      tokens.add(generator.generateRawToken());
    }
    assertThat(tokens).hasSize(1000);
  }

  @Test
  void hash_returnsLowercaseHexOfLength64() {
    String token = generator.generateRawToken();

    String hash = generator.hash(token);
    assertThat(hash).hasSize(64).matches("^[0-9a-f]+$");
  }

  @Test
  void hash_isDeterministicForSameInput() {
    String token = "fixed-token-for-determinism-check";

    assertThat(generator.hash(token)).isEqualTo(generator.hash(token));
  }

  @Test
  void hash_differs_betweenDifferentRawTokens() {
    String tokenA = generator.generateRawToken();
    String tokenB = generator.generateRawToken();

    assertThat(generator.hash(tokenA)).isNotEqualTo(generator.hash(tokenB));
  }

  @Test
  void hash_isNotEqualToRawToken() {
    String token = generator.generateRawToken();

    // The whole point of hash-only persistence: a DB read of token_hash must not yield the
    // raw value used in the cookie.
    assertThat(generator.hash(token)).isNotEqualTo(token);
  }

  @Test
  void hash_rejectsNull() {
    assertThatThrownBy(() -> generator.hash(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hash_matchesKnownSha256Vector() {
    // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
    assertThat(generator.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
