package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.domain.entity.AgeRestriction;
import com.example.mealprep.preference.domain.entity.AllergenDerivative;
import com.example.mealprep.preference.domain.entity.DietaryIdentityException;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import com.example.mealprep.preference.domain.repository.AllergenDerivativeRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.service.internal.HardConstraintFilterServiceImpl;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Targets the {@code filterRecipes} short-circuit path of {@link HardConstraintFilterServiceImpl}.
 *
 * <p>{@code filterRecipes} routes through the dedicated {@code anyViolationForKey} short-circuit
 * matcher (NOT {@code collectViolationsForKey} used by {@code check}). At baseline only the
 * direct-allergy branch of {@code anyViolationForKey} was exercised; every other branch
 * (derivative, intolerance, medical-diet direct, medical-diet implicit expansion, dietary-base
 * gated by exception, and the age-rule prefix match) survived as "replace boolean return with
 * false" / negated-conditional mutants. Each test below makes EXACTLY ONE branch the sole reason a
 * recipe is filtered out, so flipping that branch's return flips the observable result. The
 * exception-widening case also pins the {@code && !exceptionAllows} negation on the dietary-base
 * branch. Repos mocked at the module boundary; matcher logic is real.
 */
@ExtendWith(MockitoExtension.class)
class HardConstraintFilterShortCircuitTest {

  @Mock private HardConstraintsRepository hardConstraintsRepository;
  @Mock private AllergenDerivativeRepository allergenDerivativeRepository;

  private HardConstraintFilterServiceImpl service() {
    return new HardConstraintFilterServiceImpl(
        hardConstraintsRepository, allergenDerivativeRepository);
  }

  private List<UUID> filterOne(UUID userId, List<String> ingredients) {
    UUID recipe = UUID.randomUUID();
    return service().filterRecipes(userId, Map.of(recipe, ingredients));
  }

  @Test
  void filterRecipes_derivativeAllergen_excludesRecipe() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll())
        .thenReturn(List.of(new AllergenDerivative(UUID.randomUUID(), "peanut", "peanut_oil")));

    // peanut_oil is NOT a direct allergy — only the derivative branch can exclude it.
    assertThat(filterOne(userId, List.of("peanut_oil"))).isEmpty();
    // sanity: a recipe with no derivative passes (branch is specific, not blanket-true).
    assertThat(filterOne(userId, List.of("rice"))).hasSize(1);
  }

  @Test
  void filterRecipes_intolerance_excludesRecipe() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate
        .getIntolerances()
        .add(
            HardIntolerance.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .substance("milk")
                .severity("moderate")
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    assertThat(filterOne(userId, List.of("milk"))).isEmpty();
    assertThat(filterOne(userId, List.of("rice"))).hasSize(1);
  }

  @Test
  void filterRecipes_medicalDietDirectName_excludesRecipe() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withMedicalDiets("low_sodium")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    // The diet NAME itself appearing as an ingredient key hits the direct medicalDiets branch.
    assertThat(filterOne(userId, List.of("low_sodium"))).isEmpty();
  }

  @Test
  void filterRecipes_medicalDietImplicitExpansion_excludesRecipe() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withMedicalDiets("low_sodium")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    // 'salt' is not the diet name; only the medicalDietExpansions branch rejects it.
    assertThat(filterOne(userId, List.of("salt"))).isEmpty();
    assertThat(filterOne(userId, List.of("rice"))).hasSize(1);
  }

  @Test
  void filterRecipes_dietaryBaseExclusion_excludesRecipe() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegan")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    assertThat(filterOne(userId, List.of("chicken"))).isEmpty();
  }

  @Test
  void filterRecipes_dietaryBaseExcepted_recipePasses_pinsExceptionNegation() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegetarian")
            .build();
    aggregate
        .getExceptions()
        .add(
            DietaryIdentityException.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .allows("fish")
                .frequency("weekly")
                .context("weekend")
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    // 'fish' is excluded by the vegetarian base BUT widened by the exception → recipe must PASS.
    // Mutating the '&& !exceptionAllows.contains(key)' guard would wrongly exclude it.
    assertThat(filterOne(userId, List.of("fish"))).hasSize(1);
  }

  @Test
  void filterRecipes_ageRulePrefixMatch_excludesRecipe_butExactNonMatchPasses() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate
        .getAgeRestrictions()
        .add(
            AgeRestriction.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .ruleKey("no_whole_nuts")
                .autoPopulated(true)
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    // prefix match 'whole_nut_*' → excluded
    assertThat(filterOne(userId, List.of("whole_nut_almond"))).isEmpty();
    // exact special-case 'whole_nut' → excluded
    assertThat(filterOne(userId, List.of("whole_nut"))).isEmpty();
    // unrelated key → age rule must NOT match (kills "matchesAgeRule returns true" mutant)
    assertThat(filterOne(userId, List.of("almond_butter"))).hasSize(1);
  }

  @Test
  void filterRecipes_ageRuleWithoutPrefix_fallsBackToExactEquality() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate
        .getAgeRestrictions()
        .add(
            AgeRestriction.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .ruleKey("no_honey")
                .autoPopulated(false)
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    // No documented prefix for 'no_honey' → exact-equality fallback path.
    assertThat(filterOne(userId, List.of("no_honey"))).isEmpty();
    assertThat(filterOne(userId, List.of("honey"))).hasSize(1);
  }

  @Test
  void filterRecipes_noConstraintMatch_recipePasses() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    // None of the keys trigger any branch → passesIndex returns true (kills L256 false-mutant).
    assertThat(filterOne(userId, List.of("rice", "tomato", "basil"))).hasSize(1);
  }

  @Test
  void filterRecipes_nullKeyInList_isSkipped_notTreatedAsViolation() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    java.util.ArrayList<String> keys = new java.util.ArrayList<>();
    keys.add(null);
    keys.add("rice");
    assertThat(service().filterRecipes(userId, Map.of(UUID.randomUUID(), keys))).hasSize(1);
  }
}
