package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.domain.service.internal.InviteCodeGenerator;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InviteCodeGenerator}: alphabet, length, distinctness, and the security-
 * critical reflection check that the underlying RNG is a {@link SecureRandom} (NOT {@link
 * java.util.Random}).
 */
class InviteCodeGeneratorTest {

  private static final String ALLOWED = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

  private final InviteCodeGenerator generator = new InviteCodeGenerator();

  @Test
  void generate_producesSixteenCharCode() {
    String code = generator.generate();
    assertThat(code).hasSize(16);
  }

  @Test
  void generate_usesAlphabetWithoutAmbiguousChars() {
    for (int i = 0; i < 1000; i++) {
      String code = generator.generate();
      for (int j = 0; j < code.length(); j++) {
        char c = code.charAt(j);
        assertThat(ALLOWED.indexOf(c))
            .as("char '%s' at index %d of '%s' must be in alphabet", c, j, code)
            .isGreaterThanOrEqualTo(0);
      }
    }
  }

  @Test
  void generate_codesAreDistinctOver10kCalls() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      seen.add(generator.generate());
    }
    // 16-char codes from a 31-char alphabet have ~79 bits of entropy; 10k draws colliding is
    // astronomically unlikely. Allow a tiny slack just in case (none expected).
    assertThat(seen).hasSizeGreaterThan(9_995);
  }

  @Test
  void generate_underlyingRandomIsSecureRandom() throws Exception {
    Field randomField = InviteCodeGenerator.class.getDeclaredField("random");
    randomField.setAccessible(true);
    Object random = randomField.get(generator);
    assertThat(random).isInstanceOf(SecureRandom.class);
  }
}
