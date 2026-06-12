package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.dto.FilterContext;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.api.dto.Violation;
import com.example.mealprep.preference.domain.entity.AgeRestriction;
import com.example.mealprep.preference.domain.entity.AllergenDerivative;
import com.example.mealprep.preference.domain.entity.DietaryIdentityException;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import com.example.mealprep.preference.domain.entity.ViolationKind;
import com.example.mealprep.preference.domain.repository.AllergenDerivativeRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.service.internal.HardConstraintFilterServiceImpl;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link HardConstraintFilterServiceImpl}. Mocks the two repositories at the module
 * boundary; everything else is real (the static lookup tables and the per-key matching are pure
 * functions).
 */
@ExtendWith(MockitoExtension.class)
class HardConstraintFilterServiceImplTest {

  @Mock private HardConstraintsRepository hardConstraintsRepository;
  @Mock private AllergenDerivativeRepository allergenDerivativeRepository;

  private HardConstraintFilterServiceImpl service() {
    return new HardConstraintFilterServiceImpl(
        hardConstraintsRepository, allergenDerivativeRepository);
  }

  // ---------------- check ----------------

  @Test
  void check_userWithNoAggregate_returnsPassesWithNoViolations() {
    UUID userId = UUID.randomUUID();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId)).thenReturn(Optional.empty());

    FilterResult result = service().check(userId, List.of("chicken"), FilterContext.ANY);

    assertThat(result.passes()).isTrue();
    assertThat(result.violations()).isEmpty();
    verify(allergenDerivativeRepository, never()).findAll();
  }

  @Test
  void check_emptyIngredientList_returnsPassesWithNoViolations() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    FilterResult result = service().check(userId, List.of(), FilterContext.ANY);

    assertThat(result.passes()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void check_directAllergyMatch_returnsViolationOfKindAllergy() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    FilterResult result = service().check(userId, List.of("peanut"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Violation v = result.violations().get(0);
    assertThat(v.kind()).isEqualTo(ViolationKind.ALLERGY);
    assertThat(v.ingredientKey()).isEqualTo("peanut");
    assertThat(v.constraintValue()).isEqualTo("peanut");
    assertThat(v.userId()).isEqualTo(userId);
    assertThat(v.recipeId()).isNull();
  }

  @Test
  void check_allergyViaDerivative_returnsViolationWithOriginalAllergenAsConstraintValue() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll())
        .thenReturn(
            List.of(
                new AllergenDerivative(UUID.randomUUID(), "peanut", "peanut_oil"),
                new AllergenDerivative(UUID.randomUUID(), "dairy", "milk")));

    FilterResult result = service().check(userId, List.of("peanut_oil"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Violation v = result.violations().get(0);
    assertThat(v.kind()).isEqualTo(ViolationKind.ALLERGY);
    assertThat(v.ingredientKey()).isEqualTo("peanut_oil");
    assertThat(v.constraintValue()).isEqualTo("peanut");
  }

  @Test
  void check_intoleranceMatch_returnsViolationOfKindIntolerance() {
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

    FilterResult result = service().check(userId, List.of("milk"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Violation v = result.violations().get(0);
    assertThat(v.kind()).isEqualTo(ViolationKind.INTOLERANCE);
    assertThat(v.constraintValue()).isEqualTo("milk");
  }

  @Test
  void check_medicalDietImplicitRejection_returnsViolationOfKindMedicalDiet() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withMedicalDiets("low_sodium")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    FilterResult result = service().check(userId, List.of("salt"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Violation v = result.violations().get(0);
    assertThat(v.kind()).isEqualTo(ViolationKind.MEDICAL_DIET);
    assertThat(v.constraintValue()).isEqualTo("low_sodium");
  }

  @Test
  void check_dietaryBaseExclusion_returnsViolationOfKindDietaryBase() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegan")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    FilterResult result = service().check(userId, List.of("chicken"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Violation v = result.violations().get(0);
    assertThat(v.kind()).isEqualTo(ViolationKind.DIETARY_BASE);
    assertThat(v.constraintValue()).isEqualTo("vegan");
  }

  @Test
  void check_anyContextExceptionWidens_doesNotProduceViolationForExcepted() {
    // An exception stored with context = "any" widens the base regardless of the call context.
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate = vegetarianWithFishException(userId, "any");
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    FilterResult result = service().check(userId, List.of("fish"), FilterContext.ANY);

    assertThat(result.passes()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  // ---------------- preference-3: context-conditional exceptions ----------------

  @Test
  void check_weekendException_widensOnlyOnWeekendCall() {
    // Vegetarian + "fish on the weekend": fish is allowed under WEEKEND, blocked under WEEKDAY/ANY.
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate = vegetarianWithFishException(userId, "weekend");
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    assertThat(service().check(userId, List.of("fish"), FilterContext.WEEKEND).passes()).isTrue();
  }

  @Test
  void check_weekendException_doesNotWidenOnWeekdayCall() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate = vegetarianWithFishException(userId, "weekend");
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    FilterResult result = service().check(userId, List.of("fish"), FilterContext.WEEKDAY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    assertThat(result.violations().get(0).kind()).isEqualTo(ViolationKind.DIETARY_BASE);
  }

  @Test
  void check_weekendException_doesNotWidenOnAnyCall() {
    // The conservative default: a weekend-only relaxation must NOT apply when the context is ANY.
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate = vegetarianWithFishException(userId, "weekend");
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    FilterResult result = service().check(userId, List.of("fish"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations().get(0).kind()).isEqualTo(ViolationKind.DIETARY_BASE);
  }

  @Test
  void check_nullCallContext_treatedAsAny() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate = vegetarianWithFishException(userId, "weekend");
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    FilterResult result = service().check(userId, List.of("fish"), null);

    assertThat(result.passes()).isFalse();
  }

  // ---------------- preference-2: ambiguity flagging ----------------

  @Test
  void check_dairyAllergyWithLactoseFreeException_untaggedKey_isAmbiguous() {
    // Dairy allergy + a lactose_free exception + plain "yoghurt" (no lactose-free tag) → AMBIGUOUS.
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).withAllergies("dairy").build();
    aggregate.getExceptions().add(exception("lactose_free", "any"));
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll())
        .thenReturn(
            List.of(
                new AllergenDerivative(UUID.randomUUID(), "dairy", "yoghurt"),
                new AllergenDerivative(UUID.randomUUID(), "dairy", "lactose")));

    FilterResult result = service().check(userId, List.of("yoghurt"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Violation v = result.violations().get(0);
    assertThat(v.kind()).isEqualTo(ViolationKind.AMBIGUOUS);
    assertThat(v.ingredientKey()).isEqualTo("yoghurt");
    assertThat(v.constraintValue()).isEqualTo("dairy");
  }

  @Test
  void check_dairyAllergyWithLactoseFreeException_taggedKey_isSafe() {
    // The same exception, but the key DECLARES the lactose-free variant → decisively safe.
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).withAllergies("dairy").build();
    aggregate.getExceptions().add(exception("lactose_free", "any"));
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll())
        .thenReturn(List.of(new AllergenDerivative(UUID.randomUUID(), "dairy", "yoghurt")));

    FilterResult result =
        service().check(userId, List.of("lactose_free_yoghurt"), FilterContext.ANY);

    assertThat(result.passes()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void check_dairyAllergyWithoutAnyException_isPlainAllergyNotAmbiguous() {
    // No conditional exception → the same untagged "yoghurt" is a hard ALLERGY, not AMBIGUOUS.
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).withAllergies("dairy").build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll())
        .thenReturn(List.of(new AllergenDerivative(UUID.randomUUID(), "dairy", "yoghurt")));

    FilterResult result = service().check(userId, List.of("yoghurt"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    assertThat(result.violations().get(0).kind()).isEqualTo(ViolationKind.ALLERGY);
  }

  @Test
  void check_intoleranceWithFreeOfException_untaggedKey_isAmbiguous() {
    // The conditional-exception machinery also relaxes a hard intolerance the same way.
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate
        .getIntolerances()
        .add(
            HardIntolerance.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .substance("lactose")
                .severity("moderate")
                .build());
    aggregate.getExceptions().add(exception("lactose_free", "any"));
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    FilterResult result = service().check(userId, List.of("lactose"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    assertThat(result.violations().get(0).kind()).isEqualTo(ViolationKind.AMBIGUOUS);
  }

  private static HardConstraints vegetarianWithFishException(UUID userId, String context) {
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegetarian")
            .build();
    aggregate.getExceptions().add(exception("fish", context));
    aggregate.getExceptions().get(0).setHardConstraints(aggregate);
    return aggregate;
  }

  private static DietaryIdentityException exception(String allows, String context) {
    return DietaryIdentityException.builder()
        .id(UUID.randomUUID())
        .allows(allows)
        .frequency("weekly")
        .context(context)
        .build();
  }

  @Test
  void check_ageRestrictionPrefixMatch_returnsViolationOfKindAgeRestriction() {
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

    FilterResult result = service().check(userId, List.of("whole_nut_almond"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Violation v = result.violations().get(0);
    assertThat(v.kind()).isEqualTo(ViolationKind.AGE_RESTRICTION);
    assertThat(v.constraintValue()).isEqualTo("no_whole_nuts");
  }

  @Test
  void check_multipleViolationsOnOneIngredient_returnsAllViolations() {
    // milk = direct intolerance + medical-diet (low_phosphorus rejects milk) + dairy via
    // dietary-base (vegan).
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegan")
            .withMedicalDiets("low_phosphorus")
            .build();
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

    FilterResult result = service().check(userId, List.of("milk"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    // intolerance + medical-diet + dietary-base = 3
    assertThat(result.violations()).hasSize(3);
    assertThat(result.violations())
        .extracting(Violation::kind)
        .containsExactlyInAnyOrder(
            ViolationKind.INTOLERANCE, ViolationKind.MEDICAL_DIET, ViolationKind.DIETARY_BASE);
  }

  // ---------------- checkRecipe ----------------

  @Test
  void checkRecipe_violationCarriesSuppliedRecipeId() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    FilterResult result =
        service().checkRecipe(userId, recipeId, List.of("peanut"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    assertThat(result.violations()).hasSize(1);
    assertThat(result.violations().get(0).recipeId()).isEqualTo(recipeId);
  }

  @Test
  void checkRecipe_userWithNoAggregate_returnsPasses() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId)).thenReturn(Optional.empty());

    FilterResult result =
        service().checkRecipe(userId, recipeId, List.of("peanut"), FilterContext.ANY);

    assertThat(result.passes()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  // ---------------- filterRecipes ----------------

  @Test
  void filterRecipes_loadsAggregateOnce_regardlessOfRecipeCount() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    Map<UUID, List<String>> recipes = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      recipes.put(UUID.randomUUID(), List.of("chicken", "rice"));
    }

    List<UUID> passing = service().filterRecipes(userId, recipes, FilterContext.ANY);

    assertThat(passing).hasSize(1000);
    // The single-load contract: ONE aggregate fetch + ONE derivative fetch.
    verify(hardConstraintsRepository, times(1)).findWithChildrenByUserId(userId);
    verify(allergenDerivativeRepository, times(1)).findAll();
  }

  @Test
  void filterRecipes_returnsOnlyPassingRecipeIds() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    UUID safeRecipe = UUID.randomUUID();
    UUID unsafeRecipe = UUID.randomUUID();
    Map<UUID, List<String>> recipes = new HashMap<>();
    recipes.put(safeRecipe, List.of("chicken", "rice"));
    recipes.put(unsafeRecipe, List.of("chicken", "peanut"));

    List<UUID> passing = service().filterRecipes(userId, recipes, FilterContext.ANY);

    assertThat(passing).containsExactly(safeRecipe);
  }

  @Test
  void filterRecipes_userWithNoAggregate_passesAllRecipes() {
    UUID userId = UUID.randomUUID();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId)).thenReturn(Optional.empty());

    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    Map<UUID, List<String>> recipes = new HashMap<>();
    recipes.put(r1, List.of("peanut"));
    recipes.put(r2, List.of("chicken"));

    List<UUID> passing = service().filterRecipes(userId, recipes, FilterContext.ANY);

    assertThat(passing).containsExactlyInAnyOrder(r1, r2);
    verify(allergenDerivativeRepository, never()).findAll();
  }

  @Test
  void filterRecipes_emptyRecipeMap_returnsEmptyList() {
    UUID userId = UUID.randomUUID();

    List<UUID> passing = service().filterRecipes(userId, Map.of(), FilterContext.ANY);

    assertThat(passing).isEmpty();
    verify(hardConstraintsRepository, never()).findWithChildrenByUserId(userId);
  }

  @Test
  void filterRecipes_withinPerformanceBudget_for1000Recipes() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .withMedicalDiets("low_sodium")
            .withDietaryIdentityBase("vegetarian")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll())
        .thenReturn(List.of(new AllergenDerivative(UUID.randomUUID(), "peanut", "peanut_oil")));

    Map<UUID, List<String>> recipes = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      recipes.put(UUID.randomUUID(), List.of("rice", "tomato", "olive_oil", "basil", "garlic"));
    }

    long start = System.nanoTime();
    List<UUID> passing = service().filterRecipes(userId, recipes, FilterContext.ANY);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    // Sanity floor: vegan-base's tomato exclusion would be a problem if mismatched. With
    // vegetarian + the recipe ingredients above, every recipe should pass.
    assertThat(passing).hasSize(1000);
    // Generous ceiling — the matching is in-memory and bounded; this only catches a
    // pathological regression like "load aggregate per-recipe".
    assertThat(elapsedMs).isLessThan(1000L);
  }

  // ---------------- checkForHousehold ----------------

  @Test
  void checkForHousehold_unionViolations_attributedPerUser() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    HardConstraints aliceAggregate =
        HardConstraintsTestData.hardConstraints().withUserId(alice).withAllergies("peanut").build();
    HardConstraints bobAggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(bob)
            .withDietaryIdentityBase("vegan")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserIdIn(List.of(alice, bob)))
        .thenReturn(List.of(aliceAggregate, bobAggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    FilterResult result =
        service()
            .checkForHousehold(
                List.of(alice, bob), List.of("peanut", "chicken"), FilterContext.ANY);

    assertThat(result.passes()).isFalse();
    // alice -> ALLERGY on peanut; bob -> DIETARY_BASE on chicken.
    assertThat(result.violations())
        .anyMatch(v -> v.userId().equals(alice) && v.kind() == ViolationKind.ALLERGY)
        .anyMatch(v -> v.userId().equals(bob) && v.kind() == ViolationKind.DIETARY_BASE);
  }

  @Test
  void checkForHousehold_emptyUserList_returnsPasses() {
    FilterResult result =
        service().checkForHousehold(List.of(), List.of("chicken"), FilterContext.ANY);

    assertThat(result.passes()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void checkForHousehold_noUserHasAggregate_returnsPasses() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    when(hardConstraintsRepository.findWithChildrenByUserIdIn(List.of(alice, bob)))
        .thenReturn(List.of());

    FilterResult result =
        service().checkForHousehold(List.of(alice, bob), List.of("peanut"), FilterContext.ANY);

    assertThat(result.passes()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  // ---------------- exclusionKeySnapshot (discovery-server-side-exclusions) ----------------

  @Test
  void exclusionKeySnapshot_userWithNoAggregate_returnsEmptySet_neverThrows() {
    UUID userId = UUID.randomUUID();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId)).thenReturn(Optional.empty());

    assertThat(service().exclusionKeySnapshot(userId, FilterContext.ANY)).isEmpty();
    verify(allergenDerivativeRepository, never()).findAll();
  }

  @Test
  void exclusionKeySnapshot_allergies_includeDirectAndDerivativeKeys() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll())
        .thenReturn(
            List.of(
                new AllergenDerivative(UUID.randomUUID(), "peanut", "peanut_oil"),
                new AllergenDerivative(UUID.randomUUID(), "peanut", "satay_sauce"),
                // Another allergen's derivative must NOT leak into this user's snapshot.
                new AllergenDerivative(UUID.randomUUID(), "dairy", "milk")));

    var snapshot = service().exclusionKeySnapshot(userId, FilterContext.ANY);

    assertThat(snapshot)
        .contains("peanut", "peanut_oil", "satay_sauce")
        .doesNotContain("milk", "dairy");
  }

  @Test
  void exclusionKeySnapshot_includesIntolerances_medicalDietsWithExpansions_andBaseExclusions() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegetarian")
            .withMedicalDiets("low_sodium")
            .build();
    aggregate
        .getIntolerances()
        .add(
            HardIntolerance.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .substance("lactose")
                .severity("moderate")
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    var snapshot = service().exclusionKeySnapshot(userId, FilterContext.ANY);

    assertThat(snapshot)
        // intolerance substance
        .contains("lactose")
        // medical diet name + its static rejected-key expansion
        .contains("low_sodium", "salt", "soy_sauce")
        // dietary base exclusions (vegetarian = meat + fish)
        .contains("chicken", "fish");
  }

  @Test
  void exclusionKeySnapshot_plainExceptionMatchingContext_removesBaseExclusionKey() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegetarian")
            .build();
    aggregate.getExceptions().add(exception("fish", "any"));
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    var snapshot = service().exclusionKeySnapshot(userId, FilterContext.ANY);

    // "any"-context exception widens the base diet — fish is allowed, chicken still excluded.
    assertThat(snapshot).doesNotContain("fish").contains("chicken");
  }

  @Test
  void exclusionKeySnapshot_contextConditionalException_doesNotWidenUnderAnyContext() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegetarian")
            .build();
    aggregate.getExceptions().add(exception("fish", "weekend"));
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    var snapshot = service().exclusionKeySnapshot(userId, FilterContext.ANY);

    // ANY admits only "any"-context exceptions — the conservative default for a safety snapshot.
    assertThat(snapshot).contains("fish");
  }

  @Test
  void exclusionKeySnapshot_conditionallyRelaxedSubstance_staysInTheSnapshot() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).withAllergies("dairy").build();
    aggregate.getExceptions().add(exception("lactose_free", "any"));
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll())
        .thenReturn(List.of(new AllergenDerivative(UUID.randomUUID(), "dairy", "lactose")));

    var snapshot = service().exclusionKeySnapshot(userId, FilterContext.ANY);

    // Exact set membership cannot express the free-of qualifier; the conservative snapshot keeps
    // both the allergen and its derivative (matching anyViolationForKey's AMBIGUOUS-drops-it
    // pool semantics for the bare keys).
    assertThat(snapshot).contains("dairy", "lactose");
  }
}
