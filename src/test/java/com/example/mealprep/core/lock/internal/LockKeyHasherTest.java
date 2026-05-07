package com.example.mealprep.core.lock.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.lock.LockKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SHA-256-truncated-to-64-bits hash that maps {@link LockKey} to the {@code
 * bigint} arg of {@code pg_try_advisory_xact_lock}.
 *
 * <p>Covers determinism, distinguishability across the three factory shapes, and a 1000-key
 * collision sweep — collisions on 64 bits are vanishingly rare and we expect zero on this sample.
 */
class LockKeyHasherTest {

  @Test
  void hash_isDeterministic_acrossInvocations() {
    LockKey key = LockKey.forRecipe(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    long first = LockKeyHasher.hash(key);
    long second = LockKeyHasher.hash(key);
    assertThat(first).isEqualTo(second);
  }

  @Test
  void hash_differsAcrossDifferentKeys() {
    LockKey a = LockKey.forRecipe(UUID.randomUUID());
    LockKey b = LockKey.forRecipe(UUID.randomUUID());
    assertThat(LockKeyHasher.hash(a)).isNotEqualTo(LockKeyHasher.hash(b));
  }

  @Test
  void hash_differsAcrossKindsForOtherwiseSimilarInputs() {
    UUID id = UUID.randomUUID();
    LockKey recipe = LockKey.forRecipe(id);
    LockKey custom = LockKey.forCustom("recipe", id);
    // Same kind/id but routed via different factories — serialization differs, so does the hash.
    assertThat(LockKeyHasher.hash(recipe)).isNotEqualTo(LockKeyHasher.hash(custom));
  }

  @Test
  void hashBytes_takesFirst8BytesOfSha256AsSignedLong() {
    byte[] empty = new byte[0];
    long actual = LockKeyHasher.hashBytes(empty);
    // SHA-256("") = e3b0c44298fc1c14...; first 8 bytes interpreted as signed big-endian:
    // 0xE3B0C44298FC1C14 -> -2039914840885289452
    assertThat(actual).isEqualTo(0xE3B0C44298FC1C14L);
  }

  @Test
  void hashBytes_changesWhenInputChanges() {
    long a = LockKeyHasher.hashBytes("hello".getBytes(StandardCharsets.UTF_8));
    long b = LockKeyHasher.hashBytes("hellp".getBytes(StandardCharsets.UTF_8));
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void hash_planWeekKey_differsByHouseholdAndWeek() {
    UUID household = UUID.randomUUID();
    long weekA = LockKeyHasher.hash(LockKey.forPlanWeek(household, LocalDate.of(2026, 6, 1)));
    long weekB = LockKeyHasher.hash(LockKey.forPlanWeek(household, LocalDate.of(2026, 6, 8)));
    assertThat(weekA).isNotEqualTo(weekB);

    UUID otherHousehold = UUID.randomUUID();
    long otherHouseholdSameWeek =
        LockKeyHasher.hash(LockKey.forPlanWeek(otherHousehold, LocalDate.of(2026, 6, 1)));
    assertThat(weekA).isNotEqualTo(otherHouseholdSameWeek);
  }

  @Test
  void hash_1000RandomKeys_haveNoCollisions() {
    Set<Long> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      long h = LockKeyHasher.hash(LockKey.forRecipe(UUID.randomUUID()));
      assertThat(seen.add(h)).as("collision on iteration %d for hash %d", i, h).isTrue();
    }
    assertThat(seen).hasSize(1000);
  }
}
