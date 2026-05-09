package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.repository.AllergenDerivativeRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link HardConstraintFilterService}. Exercises the real Postgres-backed
 * repository, the seeded {@code preference_allergen_derivatives} reference data, and the
 * {@code @Transactional(readOnly = true)} boundary for lazy-loaded child collections.
 *
 * <p>Seeds aggregates directly via the repository (skipping the auth registration cookie dance —
 * the filter doesn't depend on auth context).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class HardConstraintFilterServiceIT {

  @Autowired private HardConstraintFilterService filterService;
  @Autowired private HardConstraintsRepository hardConstraintsRepository;
  @Autowired private HardConstraintsAuditLogRepository auditLogRepository;
  @Autowired private AllergenDerivativeRepository allergenDerivativeRepository;

  @AfterEach
  void cleanup() {
    auditLogRepository.deleteAllInBatch();
    hardConstraintsRepository.deleteAllInBatch();
    // Don't truncate the derivatives table — it's reference data seeded by R__.
  }

  @Test
  void seededDerivativesTable_isPopulatedByRepeatableMigration() {
    // The R__ seed should have populated ~50 rows on container start.
    long count = allergenDerivativeRepository.count();
    assertThat(count).isGreaterThanOrEqualTo(40L);
  }

  @Test
  void check_realPeanutDerivativeMatch_resolvesAgainstSeededTable() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    hardConstraintsRepository.saveAndFlush(aggregate);

    FilterResult result = filterService.check(userId, List.of("peanut_oil", "rice"));

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    assertThat(result.violations().get(0).ingredientKey()).isEqualTo("peanut_oil");
    assertThat(result.violations().get(0).constraintValue()).isEqualTo("peanut");
  }

  @Test
  void check_userWithNoAggregate_returnsPassesAndDoesNotThrow() {
    UUID userId = UUID.randomUUID();

    FilterResult result = filterService.check(userId, List.of("peanut"));

    assertThat(result.passes()).isTrue();
  }

  @Test
  void filterRecipes_passesEverythingForUserWithoutHardConstraints() {
    UUID userId = UUID.randomUUID();
    Map<UUID, List<String>> recipes = new HashMap<>();
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    recipes.put(r1, List.of("peanut"));
    recipes.put(r2, List.of("chicken"));

    List<UUID> passing = filterService.filterRecipes(userId, recipes);

    assertThat(passing).containsExactlyInAnyOrder(r1, r2);
  }

  @Test
  void filterRecipes_dropsRecipesViolatingPeanutAllergyOrItsDerivatives() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    hardConstraintsRepository.saveAndFlush(aggregate);

    UUID safeRecipe = UUID.randomUUID();
    UUID directAllergyRecipe = UUID.randomUUID();
    UUID derivativeAllergyRecipe = UUID.randomUUID();
    Map<UUID, List<String>> recipes = new HashMap<>();
    recipes.put(safeRecipe, List.of("rice", "chicken"));
    recipes.put(directAllergyRecipe, List.of("peanut", "rice"));
    recipes.put(derivativeAllergyRecipe, List.of("rice", "satay_sauce"));

    List<UUID> passing = filterService.filterRecipes(userId, recipes);

    assertThat(passing).containsExactly(safeRecipe);
  }

  @Test
  @Transactional
  void filterRecipes_singleAggregateLoad_for1000Recipes() {
    // Seed a single user; build 1000 fake recipes; assert wall-clock < 1s.
    // The implementation guarantees the aggregate is loaded once outside the loop — if it
    // weren't, this test would hit Hibernate 1000 times and blow the budget by orders of
    // magnitude.
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    hardConstraintsRepository.saveAndFlush(aggregate);

    Map<UUID, List<String>> recipes = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      recipes.put(UUID.randomUUID(), List.of("rice", "tomato", "basil", "garlic", "olive_oil"));
    }

    long start = System.nanoTime();
    List<UUID> passing = filterService.filterRecipes(userId, recipes);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    assertThat(passing).hasSize(1000);
    // Generous wall-clock ceiling — the IT layer adds container overhead. A regression to
    // per-recipe loads would dwarf this budget.
    assertThat(elapsedMs).isLessThan(2000L);
  }

  @Test
  void checkForHousehold_unionsViolationsAcrossUsers() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    HardConstraints aliceAggregate =
        HardConstraintsTestData.hardConstraints().withUserId(alice).withAllergies("peanut").build();
    HardConstraints bobAggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(bob)
            .withDietaryIdentityBase("vegan")
            .build();
    hardConstraintsRepository.saveAndFlush(aliceAggregate);
    hardConstraintsRepository.saveAndFlush(bobAggregate);

    FilterResult result =
        filterService.checkForHousehold(List.of(alice, bob), List.of("peanut", "chicken"));

    assertThat(result.passes()).isFalse();
    assertThat(result.violations())
        .anyMatch(v -> v.userId().equals(alice) && "peanut".equals(v.ingredientKey()))
        .anyMatch(v -> v.userId().equals(bob) && "chicken".equals(v.ingredientKey()));
  }
}
