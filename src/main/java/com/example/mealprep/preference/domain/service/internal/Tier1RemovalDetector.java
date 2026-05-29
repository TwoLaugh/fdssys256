package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.RemovedTier1Constraint;
import com.example.mealprep.preference.api.dto.Tier1Category;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic diff that identifies any safety-critical Tier-1 hard constraint a hard-constraints
 * PUT would <em>remove</em> from the stored aggregate (GAP-04). Pure function over the stored
 * aggregate + the incoming request — no I/O, no Spring — so the gate logic is unit-testable in
 * isolation and the mutation tests land on real branches.
 *
 * <p>Gated Tier-1 categories (see {@code design/preference-model.md} / {@code lld/preference.md}):
 *
 * <ul>
 *   <li><b>Allergies</b> — any stored allergen absent from the request.
 *   <li><b>Medical diets</b> — any stored medical diet absent from the request.
 *   <li><b>Severe intolerances</b> — any stored hard-intolerance <em>substance</em> absent from the
 *       request (these run through the same deterministic filter as allergies).
 *   <li><b>Dietary-identity base</b> — a change that <em>relaxes</em> the safe default the
 *       deterministic filter uses: the new base excludes a strict subset of the food classes the
 *       stored base excluded (e.g. vegan→vegetarian, vegetarian→omnivore). Tightening the base
 *       (omnivore→vegetarian) is NOT gated — it adds protection, not removes it. A lateral switch
 *       between incomparable identities (e.g. vegetarian→keto) is not a base <em>removal</em>; any
 *       genuine allergen exposure it implies is still covered by the allergy/intolerance gates.
 * </ul>
 *
 * <p><b>Not gated:</b> additions, reorderings, label-only edits, exception edits, and age
 * restrictions (auto-managed for child profiles). Comparison is case-insensitive and trims
 * surrounding whitespace so a cosmetic re-typing of the same allergen is not mistaken for a
 * removal.
 */
final class Tier1RemovalDetector {

  private Tier1RemovalDetector() {}

  /**
   * The animal-product food classes each known dietary-identity {@code base} EXCLUDES. The safe
   * default the filter applies widens as this set shrinks, so a base change is a safety-relevant
   * relaxation exactly when the new base's exclusion set is a strict subset of the stored one.
   * Bases not in this map (KETO / PALEO / OTHER / custom) have no comparable exclusion set, so a
   * transition involving them is treated as a lateral identity switch, not a base removal.
   */
  private static final Map<String, Set<String>> BASE_EXCLUDES =
      Map.of(
          "omnivore", Set.of(),
          "pescatarian", Set.of("meat", "poultry"),
          "vegetarian", Set.of("meat", "poultry", "fish"),
          "vegan", Set.of("meat", "poultry", "fish", "dairy", "eggs", "honey"));

  /**
   * @return the Tier-1 constraints the request would remove, in a stable category-then-value order;
   *     empty if the request removes none (so the caller can apply it one-step).
   */
  static List<RemovedTier1Constraint> detectRemovals(
      HardConstraints stored, UpdateHardConstraintsRequest request) {
    List<RemovedTier1Constraint> removed = new ArrayList<>();

    // Allergies: any stored allergen not present (case-insensitively) in the request.
    Set<String> requestAllergies = normalisedSet(request.allergies());
    for (String stockAllergen : nullSafe(stored.getAllergies())) {
      if (!requestAllergies.contains(normalise(stockAllergen))) {
        removed.add(new RemovedTier1Constraint(Tier1Category.ALLERGY, stockAllergen));
      }
    }

    // Medical diets: any stored diet not present in the request.
    Set<String> requestMedicalDiets = normalisedSet(request.medicalDiets());
    for (String storedDiet : nullSafe(stored.getMedicalDiets())) {
      if (!requestMedicalDiets.contains(normalise(storedDiet))) {
        removed.add(new RemovedTier1Constraint(Tier1Category.MEDICAL_DIET, storedDiet));
      }
    }

    // Severe intolerances: keyed on substance (the safety-relevant identity), not the full triple —
    // editing a stored intolerance's severity/notes is not a removal, but dropping the substance
    // is.
    Set<String> requestIntoleranceSubstances = new LinkedHashSet<>();
    for (HardIntoleranceDto dto : nullSafeDtos(request.intolerances())) {
      if (dto != null && dto.substance() != null) {
        requestIntoleranceSubstances.add(normalise(dto.substance()));
      }
    }
    for (HardIntolerance stored0 : nullSafeEntities(stored.getIntolerances())) {
      String substance = stored0 == null ? null : stored0.getSubstance();
      if (substance != null && !requestIntoleranceSubstances.contains(normalise(substance))) {
        removed.add(new RemovedTier1Constraint(Tier1Category.SEVERE_INTOLERANCE, substance));
      }
    }

    // Dietary-identity base: gate only a RELAXATION of the safe default the deterministic filter
    // uses (new base excludes a strict subset of what the stored base excluded). Tightening
    // (omnivore→vegetarian) or a lateral switch to/from an incomparable base (keto/paleo/other) is
    // not a base removal. A null stored base (never expected post-initialise) means nothing to
    // drop.
    String storedBase = stored.getDietaryIdentityBase();
    String requestBase =
        request.dietaryIdentity() == null ? null : request.dietaryIdentity().base();
    if (storedBase != null
        && !normalise(storedBase).equals(normalise(requestBase))
        && isBaseRelaxation(storedBase, requestBase)) {
      removed.add(new RemovedTier1Constraint(Tier1Category.DIETARY_IDENTITY_BASE, storedBase));
    }

    return removed;
  }

  /**
   * @return true when changing from {@code storedBase} to {@code requestBase} relaxes the filter's
   *     safe default — i.e. both bases are comparable (in {@link #BASE_EXCLUDES}) and the new base
   *     excludes a strict subset of the food classes the stored base excluded. Incomparable
   *     transitions (an unknown/keto/paleo/other base on either side) return false.
   */
  private static boolean isBaseRelaxation(String storedBase, String requestBase) {
    Set<String> storedExcludes = BASE_EXCLUDES.get(normalise(storedBase));
    Set<String> requestExcludes = BASE_EXCLUDES.get(normalise(requestBase));
    if (storedExcludes == null || requestExcludes == null) {
      return false;
    }
    // Proper subset: every class the new base excludes was already excluded by the stored base
    // (containsAll), AND at least one class the stored base excluded is no longer excluded
    // (!containsAll the other way) — i.e. the new base genuinely drops protection.
    return storedExcludes.containsAll(requestExcludes)
        && !requestExcludes.containsAll(storedExcludes);
  }

  private static Set<String> normalisedSet(List<String> values) {
    Set<String> out = new LinkedHashSet<>();
    for (String v : nullSafe(values)) {
      if (v != null) {
        out.add(normalise(v));
      }
    }
    return out;
  }

  private static String normalise(String value) {
    return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static List<String> nullSafe(List<String> in) {
    return in == null ? List.of() : in;
  }

  private static List<HardIntoleranceDto> nullSafeDtos(List<HardIntoleranceDto> in) {
    return in == null ? List.of() : in;
  }

  private static List<HardIntolerance> nullSafeEntities(List<HardIntolerance> in) {
    return in == null ? List.of() : in;
  }
}
