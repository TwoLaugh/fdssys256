package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroTotals;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * G04 contract test (the engine-side piece G04 deferred): pins the graph boundary's nutrient-key
 * vocabulary against the spike's FROZEN translation table {@code export/nutrition_keys.json}
 * (version 1, {@code culinary-graph-spike}).
 *
 * <p>Per contract-test convention the table's engine-key claims are HARDCODED here as a pinned copy
 * — this test intentionally breaks loudly if either side moves: a spike-side table edit without a
 * {@code _meta.version} bump + re-seed (G05) + re-compare (G08) is a boundary-breaking change, and
 * an engine-side rename of a micro key would silently orphan every seeded row.
 *
 * <p>What is pinned:
 *
 * <ul>
 *   <li>the engine's 28-key micro vocabulary (the DRI/e2e-baseline key set);
 *   <li>the table's 26 mapped micro keys = the 23 spike-sourced vocabulary keys + the 3
 *       fat-breakdown keys ({@code saturated_fat_g} bridge + mono/poly);
 *   <li>the 5 engine-only keys with NO spike source (absent-not-zero; never fabricated);
 *   <li>the {@code sat → typed+micros} bridge rule ({@code saturatedFatG} typed field AND the
 *       {@code saturated_fat_g} micro key — the recompute and {@code IntakeAggregator} read the
 *       micro map, the macro row reads the typed field);
 *   <li>every typed target lands on a real {@link IngredientNutritionDocument} component (the
 *       document is what G05 seeds and the recompute multiplies).
 * </ul>
 */
class GraphNutrientKeyContractTest {

  /**
   * The engine's canonical 28-micro vocabulary (DRI defaults seed {@code
   * R__nutrition_seed_dri_defaults.sql} + e2e micro baseline). Pinned copy.
   */
  private static final Set<String> ENGINE_MICRO_VOCABULARY_28 =
      Set.of(
          "calcium_mg",
          "iron_mg",
          "magnesium_mg",
          "zinc_mg",
          "vitamin_c_mg",
          "vitamin_b12_mcg",
          "folate_mcg",
          "vitamin_a_mcg",
          "vitamin_d_mcg",
          "vitamin_e_mg",
          "vitamin_k_mcg",
          "thiamin_mg",
          "riboflavin_mg",
          "niacin_mg",
          "vitamin_b6_mg",
          "pantothenic_acid_mg",
          "biotin_mcg",
          "choline_mg",
          "phosphorus_mg",
          "potassium_mg",
          "sodium_mg",
          "chloride_mg",
          "copper_mg",
          "manganese_mg",
          "selenium_mcg",
          "iodine_mcg",
          "chromium_mcg",
          "molybdenum_mcg");

  /**
   * The bridge key: saturated fat rides BOTH the typed {@code saturatedFatG} field and this micro
   * key ({@code IntakeAggregator.SAT_FAT_MICRO_KEY} reads it from slot micros).
   */
  private static final String SAT_FAT_BRIDGE_KEY = "saturated_fat_g";

  /** {@code target} semantics from the frozen table. */
  private enum Target {
    TYPED,
    MICROS,
    TYPED_PLUS_MICROS
  }

  private record Claim(String engineKey, Target target, String typedField) {
    static Claim typed(String engineKey) {
      return new Claim(engineKey, Target.TYPED, engineKey);
    }

    static Claim micros(String engineKey) {
      return new Claim(engineKey, Target.MICROS, null);
    }
  }

  /** Pinned copy of nutrition_keys.json {@code map} (version 1, frozen): spike key → claim. */
  private static final Map<String, Claim> PINNED_MAP = pinnedMap();

  private static Map<String, Claim> pinnedMap() {
    Map<String, Claim> m = new LinkedHashMap<>();
    m.put("kcal", Claim.typed("calories"));
    m.put("p", Claim.typed("proteinG"));
    m.put("c", Claim.typed("carbsG"));
    m.put("f", Claim.typed("fatG"));
    m.put("fib", Claim.typed("fibreG"));
    m.put("sat", new Claim(SAT_FAT_BRIDGE_KEY, Target.TYPED_PLUS_MICROS, "saturatedFatG"));
    m.put("mono", Claim.micros("monounsaturated_fat_g"));
    m.put("poly", Claim.micros("polyunsaturated_fat_g"));
    m.put("fe", Claim.micros("iron_mg"));
    m.put("ca", Claim.micros("calcium_mg"));
    m.put("mg", Claim.micros("magnesium_mg"));
    m.put("k", Claim.micros("potassium_mg"));
    m.put("zn", Claim.micros("zinc_mg"));
    m.put("na", Claim.micros("sodium_mg"));
    m.put("phos", Claim.micros("phosphorus_mg"));
    m.put("cu", Claim.micros("copper_mg"));
    m.put("mang", Claim.micros("manganese_mg"));
    m.put("se", Claim.micros("selenium_mcg"));
    m.put("vitc", Claim.micros("vitamin_c_mg"));
    m.put("b1", Claim.micros("thiamin_mg"));
    m.put("b2", Claim.micros("riboflavin_mg"));
    m.put("b3", Claim.micros("niacin_mg"));
    m.put("b5", Claim.micros("pantothenic_acid_mg"));
    m.put("b6", Claim.micros("vitamin_b6_mg"));
    m.put("folate", Claim.micros("folate_mcg"));
    m.put("b12", Claim.micros("vitamin_b12_mcg"));
    m.put("vita", Claim.micros("vitamin_a_mcg"));
    m.put("vite", Claim.micros("vitamin_e_mg"));
    m.put("vitd", Claim.micros("vitamin_d_mcg"));
    m.put("vitk", Claim.micros("vitamin_k_mcg"));
    m.put("choline", Claim.micros("choline_mg"));
    return m;
  }

  /**
   * Pinned copy of {@code engine_only_no_spike_source}: engine micros with NO spike source.
   * Absent-not-zero on every graph dish (never fabricated; G08 excludes from the diff).
   */
  private static final Set<String> ENGINE_ONLY_NO_SPIKE_SOURCE =
      Set.of("biotin_mcg", "chloride_mg", "iodine_mcg", "chromium_mcg", "molybdenum_mcg");

  @Test
  void tableShapePins() {
    assertThat(PINNED_MAP).hasSize(31); // 31 spike keys translate
    assertThat(ENGINE_ONLY_NO_SPIKE_SOURCE).hasSize(5);
    assertThat(typedClaims()).hasSize(6); // calories + 4 macros + the sat bridge
    assertThat(mappedMicroKeys()).hasSize(26); // 23 vocabulary micros + 3 fat-breakdown
  }

  @Test
  void twentyEightKeyVocabularyIsExactlyCovered() {
    // spike-sourced vocabulary keys + engine-only keys partition the 28-key vocabulary
    Set<String> spikeSourcedVocabulary =
        mappedMicroKeys().stream()
            .filter(ENGINE_MICRO_VOCABULARY_28::contains)
            .collect(Collectors.toSet());
    assertThat(spikeSourcedVocabulary).hasSize(23);
    assertThat(spikeSourcedVocabulary).doesNotContainAnyElementsOf(ENGINE_ONLY_NO_SPIKE_SOURCE);

    Set<String> union = new TreeSet<>(spikeSourcedVocabulary);
    union.addAll(ENGINE_ONLY_NO_SPIKE_SOURCE);
    assertThat(union).isEqualTo(new TreeSet<>(ENGINE_MICRO_VOCABULARY_28));

    // the only mapped micros OUTSIDE the 28-key vocabulary are the 3 fat-breakdown keys
    Set<String> outside = new TreeSet<>(mappedMicroKeys());
    outside.removeAll(ENGINE_MICRO_VOCABULARY_28);
    assertThat(outside)
        .containsExactly("monounsaturated_fat_g", "polyunsaturated_fat_g", SAT_FAT_BRIDGE_KEY);
  }

  @Test
  void saturatedFatBridge() {
    Claim sat = PINNED_MAP.get("sat");
    assertThat(sat.target()).isEqualTo(Target.TYPED_PLUS_MICROS);
    assertThat(sat.engineKey()).isEqualTo(SAT_FAT_BRIDGE_KEY);
    assertThat(sat.typedField()).isEqualTo("saturatedFatG");
    // 'sat' is the ONLY bridge — every other claim is typed XOR micros
    assertThat(
            PINNED_MAP.entrySet().stream()
                .filter(e -> e.getValue().target() == Target.TYPED_PLUS_MICROS)
                .map(Map.Entry::getKey))
        .containsExactly("sat");
  }

  @Test
  void fatBreakdownKeysMatchEngineRollupConstants() {
    // the planner macro rollup reads mono/poly from micros under exactly these keys
    assertThat(PINNED_MAP.get("mono").engineKey())
        .isEqualTo(DailyMacroTotals.MONOUNSATURATED_FAT_KEY);
    assertThat(PINNED_MAP.get("poly").engineKey())
        .isEqualTo(DailyMacroTotals.POLYUNSATURATED_FAT_KEY);
  }

  @Test
  void typedTargetsLandOnRealDocumentComponents() {
    Set<String> documentComponents =
        Arrays.stream(IngredientNutritionDocument.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(Collectors.toSet());
    for (Claim claim : typedClaims()) {
      assertThat(documentComponents)
          .as(
              "typed field '%s' must be an IngredientNutritionDocument component",
              claim.typedField())
          .contains(claim.typedField());
    }
    // 'vitamins' exists on the document but the graph boundary NEVER writes it (the recompute
    // reads doc.micros() only) — G05's seeded documents keep it empty.
    assertThat(documentComponents).contains("micros", "vitamins");
  }

  @Test
  void microKeyNamingConventionHoldsUnitSuffix() {
    Pattern convention = Pattern.compile("^[a-z0-9_]+_(mg|mcg|g)$");
    Set<String> allMicroKeys = new TreeSet<>(mappedMicroKeys());
    allMicroKeys.addAll(ENGINE_ONLY_NO_SPIKE_SOURCE);
    for (String key : allMicroKeys) {
      assertThat(key).matches(convention);
    }
    assertThat(ENGINE_MICRO_VOCABULARY_28).hasSize(28);
  }

  @Test
  void noDuplicateEngineTargets() {
    // no two spike keys may translate onto the same engine micro key
    var engineMicroKeys =
        PINNED_MAP.values().stream()
            .filter(c -> c.target() != Target.TYPED)
            .map(Claim::engineKey)
            .toList();
    assertThat(engineMicroKeys).doesNotHaveDuplicates();
    var typedFields = typedClaims().stream().map(Claim::typedField).toList();
    assertThat(typedFields).doesNotHaveDuplicates();
  }

  private static Set<String> mappedMicroKeys() {
    return PINNED_MAP.values().stream()
        .filter(c -> c.target() == Target.MICROS || c.target() == Target.TYPED_PLUS_MICROS)
        .map(Claim::engineKey)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<Claim> typedClaims() {
    return PINNED_MAP.values().stream()
        .filter(c -> c.target() == Target.TYPED || c.target() == Target.TYPED_PLUS_MICROS)
        .collect(Collectors.toSet());
  }
}
