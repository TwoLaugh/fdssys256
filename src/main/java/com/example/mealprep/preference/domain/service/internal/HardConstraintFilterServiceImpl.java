package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.api.dto.Violation;
import com.example.mealprep.preference.domain.entity.AgeRestriction;
import com.example.mealprep.preference.domain.entity.DietaryIdentityException;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import com.example.mealprep.preference.domain.entity.ViolationKind;
import com.example.mealprep.preference.domain.repository.AllergenDerivativeRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of {@link HardConstraintFilterService}. Loads a user's {@code
 * HardConstraints} aggregate, expands stored allergies via the {@code
 * preference_allergen_derivatives} lookup, and walks every constraint family for each ingredient
 * key, building a {@link Violation} row per match.
 *
 * <p>{@code filterRecipes} is the hot path: the user's aggregate plus its allergen expansion is
 * loaded ONCE outside the per-recipe loop. Per-recipe iteration is then pure in-memory matching.
 *
 * <p>{@code @Transactional(readOnly = true)} — the filter is a pure read. Children of the aggregate
 * (exceptions, intolerances, age restrictions) are lazy-loaded inside this same transaction.
 */
@Service
public class HardConstraintFilterServiceImpl implements HardConstraintFilterService {

  private final HardConstraintsRepository hardConstraintsRepository;
  private final AllergenDerivativeRepository allergenDerivativeRepository;

  public HardConstraintFilterServiceImpl(
      HardConstraintsRepository hardConstraintsRepository,
      AllergenDerivativeRepository allergenDerivativeRepository) {
    this.hardConstraintsRepository = hardConstraintsRepository;
    this.allergenDerivativeRepository = allergenDerivativeRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public FilterResult check(UUID userId, List<String> ingredientMappingKeys) {
    Optional<HardConstraints> aggregate =
        hardConstraintsRepository.findWithChildrenByUserId(userId);
    if (aggregate.isEmpty()) {
      return new FilterResult(true, List.of());
    }
    UserConstraintIndex index = buildIndex(aggregate.get());
    List<Violation> violations = collectViolations(index, ingredientMappingKeys, null);
    return new FilterResult(violations.isEmpty(), violations);
  }

  @Override
  @Transactional(readOnly = true)
  public FilterResult checkRecipe(
      UUID userId, UUID recipeId, List<String> recipeIngredientMappingKeys) {
    Optional<HardConstraints> aggregate =
        hardConstraintsRepository.findWithChildrenByUserId(userId);
    if (aggregate.isEmpty()) {
      return new FilterResult(true, List.of());
    }
    UserConstraintIndex index = buildIndex(aggregate.get());
    List<Violation> violations = collectViolations(index, recipeIngredientMappingKeys, recipeId);
    return new FilterResult(violations.isEmpty(), violations);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> filterRecipes(UUID userId, Map<UUID, List<String>> recipesIngredientKeys) {
    if (recipesIngredientKeys == null || recipesIngredientKeys.isEmpty()) {
      return List.of();
    }
    Optional<HardConstraints> aggregate =
        hardConstraintsRepository.findWithChildrenByUserId(userId);
    if (aggregate.isEmpty()) {
      // No constraints means everything passes.
      return new ArrayList<>(recipesIngredientKeys.keySet());
    }
    UserConstraintIndex index = buildIndex(aggregate.get());
    List<UUID> passingRecipes = new ArrayList<>();
    for (Map.Entry<UUID, List<String>> entry : recipesIngredientKeys.entrySet()) {
      if (passesIndex(index, entry.getValue())) {
        passingRecipes.add(entry.getKey());
      }
    }
    return passingRecipes;
  }

  @Override
  @Transactional(readOnly = true)
  public FilterResult checkForHousehold(List<UUID> userIds, List<String> ingredientMappingKeys) {
    if (userIds == null || userIds.isEmpty()) {
      return new FilterResult(true, List.of());
    }
    List<HardConstraints> aggregates =
        hardConstraintsRepository.findWithChildrenByUserIdIn(userIds);
    if (aggregates.isEmpty()) {
      return new FilterResult(true, List.of());
    }
    List<Violation> all = new ArrayList<>();
    for (HardConstraints aggregate : aggregates) {
      UserConstraintIndex index = buildIndex(aggregate);
      all.addAll(collectViolations(index, ingredientMappingKeys, null));
    }
    return new FilterResult(all.isEmpty(), all);
  }

  // ---------------- internals ----------------

  /** Per-user denormalised index of all the data the per-key match needs. Built ONCE per call. */
  private static final class UserConstraintIndex {
    final UUID userId;
    final Set<String> directAllergies;
    final Map<String, String> derivativeToAllergen;
    final Map<String, String> intolerances;
    final Set<String> medicalDiets;
    final Map<String, String> medicalDietExpansions;
    final String dietaryIdentityBase;
    final Set<String> dietaryIdentityExclusions;
    final Set<String> dietaryIdentityExceptionAllows;
    final List<String> ageRestrictionRuleKeys;

    UserConstraintIndex(
        UUID userId,
        Set<String> directAllergies,
        Map<String, String> derivativeToAllergen,
        Map<String, String> intolerances,
        Set<String> medicalDiets,
        Map<String, String> medicalDietExpansions,
        String dietaryIdentityBase,
        Set<String> dietaryIdentityExclusions,
        Set<String> dietaryIdentityExceptionAllows,
        List<String> ageRestrictionRuleKeys) {
      this.userId = userId;
      this.directAllergies = directAllergies;
      this.derivativeToAllergen = derivativeToAllergen;
      this.intolerances = intolerances;
      this.medicalDiets = medicalDiets;
      this.medicalDietExpansions = medicalDietExpansions;
      this.dietaryIdentityBase = dietaryIdentityBase;
      this.dietaryIdentityExclusions = dietaryIdentityExclusions;
      this.dietaryIdentityExceptionAllows = dietaryIdentityExceptionAllows;
      this.ageRestrictionRuleKeys = ageRestrictionRuleKeys;
    }
  }

  private UserConstraintIndex buildIndex(HardConstraints aggregate) {
    Set<String> directAllergies =
        aggregate.getAllergies() == null ? Set.of() : new HashSet<>(aggregate.getAllergies());

    Map<String, String> derivativeToAllergen = new HashMap<>();
    if (!directAllergies.isEmpty()) {
      // ONE expansion query per call; the individual allergens map back to the matched derivative.
      // We need the reverse mapping (derivative -> allergen), so re-fetch grouped.
      // For simplicity: query all rows whose allergen is in the user's set, build the map.
      List<com.example.mealprep.preference.domain.entity.AllergenDerivative> rows =
          findDerivativeRows(directAllergies);
      for (com.example.mealprep.preference.domain.entity.AllergenDerivative row : rows) {
        derivativeToAllergen.put(row.getDerivative(), row.getAllergen());
      }
    }

    Map<String, String> intolerances = new HashMap<>();
    for (HardIntolerance hi : aggregate.getIntolerances()) {
      intolerances.put(hi.getSubstance(), hi.getSubstance());
    }

    Set<String> medicalDiets =
        aggregate.getMedicalDiets() == null ? Set.of() : new HashSet<>(aggregate.getMedicalDiets());
    Map<String, String> medicalDietExpansions = new HashMap<>();
    for (String diet : medicalDiets) {
      for (String rejected : MedicalDietRules.rejectedKeysFor(diet)) {
        medicalDietExpansions.putIfAbsent(rejected, diet);
      }
    }

    String dietaryIdentityBase = aggregate.getDietaryIdentityBase();
    Set<String> dietaryIdentityExclusions =
        new HashSet<>(DietaryBaseExclusions.exclusionsFor(dietaryIdentityBase));
    Set<String> exceptionAllows = new HashSet<>();
    for (DietaryIdentityException ex : aggregate.getExceptions()) {
      if (ex.getAllows() != null) {
        exceptionAllows.add(ex.getAllows());
      }
    }

    List<String> ruleKeys = new ArrayList<>();
    for (AgeRestriction ar : aggregate.getAgeRestrictions()) {
      if (ar.getRuleKey() != null) {
        ruleKeys.add(ar.getRuleKey());
      }
    }

    return new UserConstraintIndex(
        aggregate.getUserId(),
        directAllergies,
        derivativeToAllergen,
        intolerances,
        medicalDiets,
        medicalDietExpansions,
        dietaryIdentityBase,
        dietaryIdentityExclusions,
        exceptionAllows,
        ruleKeys);
  }

  /**
   * Fetches the full {@code AllergenDerivative} rows for the supplied allergens. The repository
   * exposes a derivative-only projection; for the reverse (derivative-&gt;allergen) map we fetch
   * the rows directly. ONE query per filter call.
   */
  private List<com.example.mealprep.preference.domain.entity.AllergenDerivative> findDerivativeRows(
      Collection<String> allergens) {
    // Repo doesn't expose an entity-list query; the lookup table is small (~50 rows in v1) so
    // findAll-and-filter is cheap and avoids a custom query just for the reverse map. Revisit if
    // the table grows materially.
    List<com.example.mealprep.preference.domain.entity.AllergenDerivative> all =
        allergenDerivativeRepository.findAll();
    List<com.example.mealprep.preference.domain.entity.AllergenDerivative> filtered =
        new ArrayList<>(all.size());
    for (com.example.mealprep.preference.domain.entity.AllergenDerivative row : all) {
      if (allergens.contains(row.getAllergen())) {
        filtered.add(row);
      }
    }
    return filtered;
  }

  private List<Violation> collectViolations(
      UserConstraintIndex index, List<String> ingredientKeys, UUID recipeId) {
    if (ingredientKeys == null || ingredientKeys.isEmpty()) {
      return List.of();
    }
    List<Violation> violations = new ArrayList<>();
    for (String key : ingredientKeys) {
      if (key == null) {
        continue;
      }
      collectViolationsForKey(index, key, recipeId, violations);
    }
    return violations;
  }

  private boolean passesIndex(UserConstraintIndex index, List<String> ingredientKeys) {
    if (ingredientKeys == null || ingredientKeys.isEmpty()) {
      return true;
    }
    for (String key : ingredientKeys) {
      if (key == null) {
        continue;
      }
      if (anyViolationForKey(index, key)) {
        return false;
      }
    }
    return true;
  }

  private void collectViolationsForKey(
      UserConstraintIndex index, String key, UUID recipeId, List<Violation> out) {
    // 1. Allergy direct
    if (index.directAllergies.contains(key)) {
      out.add(new Violation(index.userId, recipeId, key, ViolationKind.ALLERGY, key));
    }
    // 2. Allergy via derivative — constraintValue is the original allergen
    String matchingAllergen = index.derivativeToAllergen.get(key);
    if (matchingAllergen != null) {
      out.add(new Violation(index.userId, recipeId, key, ViolationKind.ALLERGY, matchingAllergen));
    }
    // 3. Intolerance
    if (index.intolerances.containsKey(key)) {
      out.add(
          new Violation(
              index.userId, recipeId, key, ViolationKind.INTOLERANCE, index.intolerances.get(key)));
    }
    // 4. Medical diet — direct match against the diet name
    if (index.medicalDiets.contains(key)) {
      out.add(new Violation(index.userId, recipeId, key, ViolationKind.MEDICAL_DIET, key));
    }
    // 5. Medical diet — implicit rejection via static rules (e.g. low_sodium → salt)
    String triggeringDiet = index.medicalDietExpansions.get(key);
    if (triggeringDiet != null) {
      out.add(
          new Violation(index.userId, recipeId, key, ViolationKind.MEDICAL_DIET, triggeringDiet));
    }
    // 6. Dietary identity base — gated by exceptions
    if (index.dietaryIdentityExclusions.contains(key)
        && !index.dietaryIdentityExceptionAllows.contains(key)) {
      out.add(
          new Violation(
              index.userId, recipeId, key, ViolationKind.DIETARY_BASE, index.dietaryIdentityBase));
    }
    // 7. Age restriction — rule_key matches the key directly OR a documented prefix pattern
    for (String rule : index.ageRestrictionRuleKeys) {
      if (matchesAgeRule(rule, key)) {
        out.add(new Violation(index.userId, recipeId, key, ViolationKind.AGE_RESTRICTION, rule));
      }
    }
  }

  /**
   * Short-circuits on first violation for {@code filterRecipes}. Same rules as {@link
   * #collectViolationsForKey} but stops at the first match.
   */
  private boolean anyViolationForKey(UserConstraintIndex index, String key) {
    if (index.directAllergies.contains(key)) {
      return true;
    }
    if (index.derivativeToAllergen.containsKey(key)) {
      return true;
    }
    if (index.intolerances.containsKey(key)) {
      return true;
    }
    if (index.medicalDiets.contains(key)) {
      return true;
    }
    if (index.medicalDietExpansions.containsKey(key)) {
      return true;
    }
    if (index.dietaryIdentityExclusions.contains(key)
        && !index.dietaryIdentityExceptionAllows.contains(key)) {
      return true;
    }
    for (String rule : index.ageRestrictionRuleKeys) {
      if (matchesAgeRule(rule, key)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Encodes the v1 age-rule matching: {@code no_whole_nuts} matches keys containing {@code
   * whole_nut_*} (i.e. keys starting with {@code whole_nut_}). Rules with no documented prefix fall
   * back to a direct equality check.
   */
  private static boolean matchesAgeRule(String ruleKey, String ingredientKey) {
    if (ruleKey == null || ingredientKey == null) {
      return false;
    }
    if ("no_whole_nuts".equals(ruleKey)) {
      return ingredientKey.startsWith("whole_nut_") || "whole_nut".equals(ingredientKey);
    }
    return ruleKey.equals(ingredientKey);
  }
}
