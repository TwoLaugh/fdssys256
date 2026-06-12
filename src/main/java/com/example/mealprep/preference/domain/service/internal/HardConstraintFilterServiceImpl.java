package com.example.mealprep.preference.domain.service.internal;

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
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * <p><b>Context-conditional exceptions (preference-3, LLD Flow 2 step 5).</b> A dietary-identity
 * exception widens the base diet only when its stored {@code context} matches the {@link
 * FilterContext} the check runs under (an {@code "any"} exception always matches). The index is
 * therefore rebuilt per {@code context}.
 *
 * <p><b>Ambiguity flagging (preference-2, LLD Flow 2 step 7).</b> When an ingredient matches an
 * allergy/intolerance that a <em>conditional</em> ("X-free") dietary-identity exception might
 * relax, but the ingredient key does not declare the free-of qualifier, the filter emits an {@link
 * ViolationKind#AMBIGUOUS} violation ({@code passes = false}) rather than silently passing — the
 * safer of the two choices. Example: a dairy allergy + a {@code lactose_free} exception +
 * ingredient key {@code yoghurt} → AMBIGUOUS; {@code lactose_free_yoghurt} → safe; with no such
 * exception → plain ALLERGY.
 *
 * <p>{@code @Transactional(readOnly = true)} — the filter is a pure read. Children of the aggregate
 * (exceptions, intolerances, age restrictions) are lazy-loaded inside this same transaction.
 */
@Service
public class HardConstraintFilterServiceImpl implements HardConstraintFilterService {

  /** Suffix marking a conditional ("free-of") dietary-identity exception, e.g. {@code _free}. */
  private static final String FREE_OF_SUFFIX = "_free";

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
  public FilterResult check(
      UUID userId, List<String> ingredientMappingKeys, FilterContext context) {
    Optional<HardConstraints> aggregate =
        hardConstraintsRepository.findWithChildrenByUserId(userId);
    if (aggregate.isEmpty()) {
      return new FilterResult(true, List.of());
    }
    UserConstraintIndex index = buildIndex(aggregate.get(), context);
    List<Violation> violations = collectViolations(index, ingredientMappingKeys, null);
    return new FilterResult(violations.isEmpty(), violations);
  }

  @Override
  @Transactional(readOnly = true)
  public FilterResult checkRecipe(
      UUID userId, UUID recipeId, List<String> recipeIngredientMappingKeys, FilterContext context) {
    Optional<HardConstraints> aggregate =
        hardConstraintsRepository.findWithChildrenByUserId(userId);
    if (aggregate.isEmpty()) {
      return new FilterResult(true, List.of());
    }
    UserConstraintIndex index = buildIndex(aggregate.get(), context);
    List<Violation> violations = collectViolations(index, recipeIngredientMappingKeys, recipeId);
    return new FilterResult(violations.isEmpty(), violations);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> filterRecipes(
      UUID userId, Map<UUID, List<String>> recipesIngredientKeys, FilterContext context) {
    if (recipesIngredientKeys == null || recipesIngredientKeys.isEmpty()) {
      return List.of();
    }
    Optional<HardConstraints> aggregate =
        hardConstraintsRepository.findWithChildrenByUserId(userId);
    if (aggregate.isEmpty()) {
      // No constraints means everything passes.
      return new ArrayList<>(recipesIngredientKeys.keySet());
    }
    UserConstraintIndex index = buildIndex(aggregate.get(), context);
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
  public FilterResult checkForHousehold(
      List<UUID> userIds, List<String> ingredientMappingKeys, FilterContext context) {
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
      UserConstraintIndex index = buildIndex(aggregate, context);
      all.addAll(collectViolations(index, ingredientMappingKeys, null));
    }
    return new FilterResult(all.isEmpty(), all);
  }

  @Override
  @Transactional(readOnly = true)
  public Set<String> exclusionKeySnapshot(UUID userId, FilterContext context) {
    Optional<HardConstraints> aggregate =
        hardConstraintsRepository.findWithChildrenByUserId(userId);
    if (aggregate.isEmpty()) {
      return Set.of();
    }
    UserConstraintIndex index = buildIndex(aggregate.get(), context);
    Set<String> keys = new HashSet<>();
    // Mirrors anyViolationForKey for every family whose match is exact set membership. Substances
    // a conditional free-of exception might relax stay IN the set: the exact substance key never
    // declares the free-of qualifier, so the per-key check treats it as a violation (AMBIGUOUS in
    // pool semantics) — the conservative choice for a safety snapshot.
    keys.addAll(index.directAllergies);
    keys.addAll(index.derivativeToAllergen.keySet());
    keys.addAll(index.intolerances.keySet());
    keys.addAll(index.medicalDiets);
    keys.addAll(index.medicalDietExpansions.keySet());
    for (String excluded : index.dietaryIdentityExclusions) {
      if (!index.dietaryIdentityExceptionAllows.contains(excluded)) {
        keys.add(excluded);
      }
    }
    // Age-restriction rules are prefix patterns (no_whole_nuts → whole_nut_*) — not enumerable as
    // exact keys; they remain covered by the check*/filterRecipes paths only.
    return Collections.unmodifiableSet(keys);
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

    /**
     * Free-of qualifier tokens (e.g. {@code lactose_free}) from conditional exceptions whose
     * context matched this call — used to relax an allergy/intolerance match to either safe (key
     * declares the qualifier) or {@link ViolationKind#AMBIGUOUS} (key does not).
     */
    final Set<String> conditionalFreeOfTokens;

    /**
     * Set of allergy/intolerance constraint substances that at least one matched conditional
     * exception could relax. A match on one of these substances is downgraded from a hard ALLERGY/
     * INTOLERANCE to a context-sensitive safe/AMBIGUOUS decision.
     */
    final Set<String> conditionallyRelaxedSubstances;

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
        List<String> ageRestrictionRuleKeys,
        Set<String> conditionalFreeOfTokens,
        Set<String> conditionallyRelaxedSubstances) {
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
      this.conditionalFreeOfTokens = conditionalFreeOfTokens;
      this.conditionallyRelaxedSubstances = conditionallyRelaxedSubstances;
    }
  }

  private UserConstraintIndex buildIndex(HardConstraints aggregate, FilterContext context) {
    FilterContext effectiveContext = context == null ? FilterContext.ANY : context;

    Set<String> directAllergies =
        aggregate.getAllergies() == null ? Set.of() : new HashSet<>(aggregate.getAllergies());

    Map<String, String> derivativeToAllergen = new HashMap<>();
    if (!directAllergies.isEmpty()) {
      // ONE expansion query per call; the individual allergens map back to the matched derivative.
      // We need the reverse mapping (derivative -> allergen), so re-fetch grouped.
      // For simplicity: query all rows whose allergen is in the user's set, build the map.
      List<AllergenDerivative> rows = findDerivativeRows(directAllergies);
      for (AllergenDerivative row : rows) {
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

    // Evaluate each exception against the call context. Conditional "X-free" exceptions feed the
    // ambiguity machinery; plain exceptions widen the base diet directly.
    Set<String> exceptionAllows = new HashSet<>();
    Set<String> conditionalFreeOfTokens = new HashSet<>();
    for (DietaryIdentityException ex : aggregate.getExceptions()) {
      if (ex.getAllows() == null || !contextMatches(effectiveContext, ex.getContext())) {
        continue;
      }
      String allows = ex.getAllows();
      if (isFreeOfQualifier(allows)) {
        conditionalFreeOfTokens.add(allows);
      } else {
        exceptionAllows.add(allows);
      }
    }

    // A conditional exception relaxes the substance it is "free of" (e.g. lactose_free -> lactose)
    // and, transitively, any allergen whose derivative set includes that substance (e.g. lactose is
    // a derivative of dairy, so a lactose_free exception relaxes a dairy allergy too).
    Set<String> conditionallyRelaxedSubstances = new HashSet<>();
    for (String token : conditionalFreeOfTokens) {
      String base = strippedFreeOfBase(token);
      if (base.isEmpty()) {
        continue;
      }
      conditionallyRelaxedSubstances.add(base);
      // If the stripped base is itself a derivative, the parent allergen is relaxed as well.
      String parentAllergen = derivativeToAllergen.get(base);
      if (parentAllergen != null) {
        conditionallyRelaxedSubstances.add(parentAllergen);
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
        ruleKeys,
        conditionalFreeOfTokens,
        conditionallyRelaxedSubstances);
  }

  /**
   * Returns {@code true} when an exception stored with {@code exceptionContext} applies under the
   * given call {@code context}. An exception with {@code context = "any"} (or null/blank) always
   * applies; otherwise the tokens must match exactly. A non-{@code ANY} call context still admits
   * {@code "any"} exceptions; an {@code ANY} call context admits <em>only</em> {@code "any"}
   * exceptions.
   */
  private static boolean contextMatches(FilterContext callContext, String exceptionContext) {
    String stored =
        exceptionContext == null || exceptionContext.isBlank()
            ? "any"
            : exceptionContext.toLowerCase(Locale.ROOT);
    if ("any".equals(stored)) {
      return true;
    }
    return callContext != FilterContext.ANY && stored.equals(callContext.token());
  }

  private static boolean isFreeOfQualifier(String allows) {
    return allows != null
        && allows.toLowerCase(Locale.ROOT).endsWith(FREE_OF_SUFFIX)
        && allows.length() > FREE_OF_SUFFIX.length();
  }

  /** {@code lactose_free} -> {@code lactose}; non-free-of input -> empty string. */
  private static String strippedFreeOfBase(String token) {
    if (!isFreeOfQualifier(token)) {
      return "";
    }
    String lower = token.toLowerCase(Locale.ROOT);
    return lower.substring(0, lower.length() - FREE_OF_SUFFIX.length());
  }

  /**
   * Whether the ingredient key decisively declares one of the matched conditional free-of
   * qualifiers (e.g. key {@code lactose_free_yoghurt} or {@code yoghurt_lactose_free} for a {@code
   * lactose_free} exception). If so, the conditional exception applies and the match is safe.
   */
  private static boolean keyDeclaresFreeOf(String key, Set<String> tokens) {
    if (key == null || tokens.isEmpty()) {
      return false;
    }
    String lower = key.toLowerCase(Locale.ROOT);
    for (String token : tokens) {
      String t = token.toLowerCase(Locale.ROOT);
      if (lower.equals(t) || lower.contains(t)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Fetches the full {@code AllergenDerivative} rows for the supplied allergens. The repository
   * exposes a derivative-only projection; for the reverse (derivative-&gt;allergen) map we fetch
   * the rows directly. ONE query per filter call.
   */
  private List<AllergenDerivative> findDerivativeRows(Collection<String> allergens) {
    // Repo doesn't expose an entity-list query; the lookup table is small (~50 rows in v1) so
    // findAll-and-filter is cheap and avoids a custom query just for the reverse map. Revisit if
    // the table grows materially.
    List<AllergenDerivative> all = allergenDerivativeRepository.findAll();
    List<AllergenDerivative> filtered = new ArrayList<>(all.size());
    for (AllergenDerivative row : all) {
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
      addAllergyOrAmbiguous(index, key, key, recipeId, out);
    }
    // 2. Allergy via derivative — constraintValue is the original allergen
    String matchingAllergen = index.derivativeToAllergen.get(key);
    if (matchingAllergen != null) {
      addAllergyOrAmbiguous(index, key, matchingAllergen, recipeId, out);
    }
    // 3. Intolerance
    if (index.intolerances.containsKey(key)) {
      String substance = index.intolerances.get(key);
      if (isConditionallyRelaxed(index, substance)) {
        addConditionalOutcome(index, key, substance, ViolationKind.INTOLERANCE, recipeId, out);
      } else {
        out.add(new Violation(index.userId, recipeId, key, ViolationKind.INTOLERANCE, substance));
      }
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
   * Emits an ALLERGY violation, unless a matched conditional exception relaxes this allergen — in
   * which case the key either decisively declares the free-of qualifier (safe, no violation) or it
   * does not ({@link ViolationKind#AMBIGUOUS}).
   */
  private void addAllergyOrAmbiguous(
      UserConstraintIndex index, String key, String allergen, UUID recipeId, List<Violation> out) {
    if (isConditionallyRelaxed(index, allergen)) {
      addConditionalOutcome(index, key, allergen, ViolationKind.ALLERGY, recipeId, out);
    } else {
      out.add(new Violation(index.userId, recipeId, key, ViolationKind.ALLERGY, allergen));
    }
  }

  /**
   * For a match against a substance a conditional exception relaxes: a key declaring the free-of
   * qualifier is safe (no violation); otherwise emit {@link ViolationKind#AMBIGUOUS} carrying the
   * original constraint substance.
   */
  private void addConditionalOutcome(
      UserConstraintIndex index,
      String key,
      String substance,
      ViolationKind originalKind,
      UUID recipeId,
      List<Violation> out) {
    if (keyDeclaresFreeOf(key, index.conditionalFreeOfTokens)) {
      // Exception decisively applies — the key is the safe, free-of variant.
      return;
    }
    // Under-determined: the relaxation might apply but the key doesn't say. Flag, don't pass.
    out.add(new Violation(index.userId, recipeId, key, ViolationKind.AMBIGUOUS, substance));
  }

  private static boolean isConditionallyRelaxed(UserConstraintIndex index, String substance) {
    return index.conditionallyRelaxedSubstances.contains(substance);
  }

  /**
   * Short-circuits on first violation for {@code filterRecipes}. Same rules as {@link
   * #collectViolationsForKey} but stops at the first match. An AMBIGUOUS outcome counts as a
   * violation (the recipe is dropped from the auto-generated pool — the safe choice; the AMBIGUOUS
   * detail is only materialised by the {@code check}/{@code checkRecipe} paths the user-facing UI
   * uses).
   */
  private boolean anyViolationForKey(UserConstraintIndex index, String key) {
    if (index.directAllergies.contains(key)) {
      if (!isConditionallyRelaxed(index, key)) {
        return true;
      }
      if (!keyDeclaresFreeOf(key, index.conditionalFreeOfTokens)) {
        return true; // AMBIGUOUS — drop from the pool
      }
    }
    String matchingAllergen = index.derivativeToAllergen.get(key);
    if (matchingAllergen != null) {
      if (!isConditionallyRelaxed(index, matchingAllergen)) {
        return true;
      }
      if (!keyDeclaresFreeOf(key, index.conditionalFreeOfTokens)) {
        return true; // AMBIGUOUS — drop from the pool
      }
    }
    if (index.intolerances.containsKey(key)) {
      if (!isConditionallyRelaxed(index, index.intolerances.get(key))) {
        return true;
      }
      if (!keyDeclaresFreeOf(key, index.conditionalFreeOfTokens)) {
        return true; // AMBIGUOUS — drop from the pool
      }
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
