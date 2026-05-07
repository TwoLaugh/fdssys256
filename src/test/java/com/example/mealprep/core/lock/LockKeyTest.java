package com.example.mealprep.core.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.core.lock.internal.LockKeyHasher;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LockKeyTest {

  @Test
  void forPlanWeek_serialisesStably_acrossInstances() {
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);

    LockKey a = LockKey.forPlanWeek(household, week);
    LockKey b = LockKey.forPlanWeek(household, week);

    assertThat(a.serialize()).isEqualTo(b.serialize());
    assertThat(a.serialize()).startsWith("plan-week|");
  }

  @Test
  void forRecipe_serialisesStably_acrossInstances() {
    UUID recipe = UUID.randomUUID();

    LockKey a = LockKey.forRecipe(recipe);
    LockKey b = LockKey.forRecipe(recipe);

    assertThat(a.serialize()).isEqualTo(b.serialize());
    assertThat(a.serialize()).isEqualTo("recipe|" + recipe);
  }

  @Test
  void forCustom_serialisesStably_acrossInstances() {
    UUID scope = UUID.randomUUID();

    LockKey a = LockKey.forCustom("import-job", scope);
    LockKey b = LockKey.forCustom("import-job", scope);

    assertThat(a.serialize()).isEqualTo(b.serialize());
    assertThat(a.serialize()).isEqualTo("custom|import-job|" + scope);
  }

  @Test
  void factories_rejectNullInputs() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> LockKey.forPlanWeek(null, LocalDate.now()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> LockKey.forPlanWeek(id, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> LockKey.forRecipe(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> LockKey.forCustom(null, id)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> LockKey.forCustom("kind", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void forCustom_rejectsBlankScopeKind() {
    assertThatThrownBy(() -> LockKey.forCustom("", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LockKey.forCustom("   ", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void differentScopes_produceDifferentSerialisations() {
    UUID id = UUID.randomUUID();
    String planWeek = LockKey.forPlanWeek(id, LocalDate.of(2026, 1, 1)).serialize();
    String recipe = LockKey.forRecipe(id).serialize();
    String custom = LockKey.forCustom("anything", id).serialize();

    assertThat(planWeek).isNotEqualTo(recipe);
    assertThat(planWeek).isNotEqualTo(custom);
    assertThat(recipe).isNotEqualTo(custom);
  }

  @Test
  void hash_isDeterministicForSameKey() {
    UUID id = UUID.randomUUID();
    LockKey a = LockKey.forRecipe(id);
    LockKey b = LockKey.forRecipe(id);

    assertThat(LockKeyHasher.hash(a)).isEqualTo(LockKeyHasher.hash(b));
  }

  @Test
  void hash_collisions_lowOverManyDistinctKeys() {
    // Sanity check: 1000 distinct keys yield 1000 distinct hashes (collision-free
    // expectation on 64-bit hash at this scale; ~1 in 18 quintillion per pair).
    Set<Long> hashes = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      hashes.add(LockKeyHasher.hash(LockKey.forRecipe(UUID.randomUUID())));
    }
    assertThat(hashes).hasSize(1000);
  }

  @Test
  void hash_acrossDifferentScopes_distinct() {
    UUID id = UUID.randomUUID();
    long planHash = LockKeyHasher.hash(LockKey.forPlanWeek(id, LocalDate.now()));
    long recipeHash = LockKeyHasher.hash(LockKey.forRecipe(id));
    long customHash = LockKeyHasher.hash(LockKey.forCustom("kind", id));

    assertThat(planHash).isNotEqualTo(recipeHash);
    assertThat(planHash).isNotEqualTo(customHash);
    assertThat(recipeHash).isNotEqualTo(customHash);
  }
}
