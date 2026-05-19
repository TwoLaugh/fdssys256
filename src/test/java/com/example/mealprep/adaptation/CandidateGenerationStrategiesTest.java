package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.service.internal.IngredientRemoveStrategy;
import com.example.mealprep.adaptation.domain.service.internal.IngredientSwapStrategy;
import com.example.mealprep.adaptation.domain.service.internal.MethodSimplificationStrategy;
import com.example.mealprep.adaptation.domain.service.internal.PortionAdjustStrategy;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.MethodStepDto;
import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeTagsDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Per-strategy unit tests targeting the edge cases the suite-level {@link CandidateGeneratorTest}
 * does not reach: stable {@code name()}s, the {@code methodSteps().size() < 2} boundary, the
 * longest-step selection, the optional-ingredient filter, the swap loop increment, and the
 * null-version / null-ingredients guards. Real strategy instances (no mocks).
 */
class CandidateGenerationStrategiesTest {

  private final AdaptationJob job =
      AdaptationJob.builder()
          .id(UUID.randomUUID())
          .recipeId(UUID.randomUUID())
          .userId(UUID.randomUUID())
          .catalogue(Catalogue.USER)
          .source(JobSource.IMPORT)
          .priority(JobPriority.SYNC)
          .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
          .status(JobStatus.RUNNING)
          .inputs(JsonNodeFactory.instance.objectNode())
          .traceId(UUID.randomUUID())
          .enqueuedAt(Instant.now())
          .build();

  // ---------------- name() identities (kill "replaced return with \"\"" NOCOV) ----------------

  @Test
  void strategy_names_are_stable() {
    assertThat(new MethodSimplificationStrategy().name()).isEqualTo("method-simplification");
    assertThat(new IngredientRemoveStrategy().name()).isEqualTo("ingredient-remove");
    assertThat(new IngredientSwapStrategy().name()).isEqualTo("ingredient-swap");
    assertThat(new PortionAdjustStrategy().name()).isEqualTo("portion-adjust");
  }

  // ---------------- MethodSimplificationStrategy ----------------

  @Test
  void methodSimplification_null_version_yields_empty() {
    assertThat(new MethodSimplificationStrategy().generate(job, context(null))).isEmpty();
  }

  @Test
  void methodSimplification_one_step_is_below_min_and_yields_empty() {
    // size() == 1 < 2 -> empty. Kills the `size() < 2` boundary mutant.
    RecipeVersionDto v = version(List.of(ing("beef", false)), List.of(step(1, "do x", 5)));
    assertThat(new MethodSimplificationStrategy().generate(job, context(v))).isEmpty();
  }

  @Test
  void methodSimplification_two_steps_emits_drop_and_collapse() {
    RecipeVersionDto v =
        version(
            List.of(ing("beef", false)),
            List.of(step(1, "short", 1), step(2, "a much longer instruction here", 9)));
    List<AdaptationCandidateDto> out = new MethodSimplificationStrategy().generate(job, context(v));
    assertThat(out).hasSize(2);
    // longest step is index 1 -> drop candidate references step 1
    assertThat(out.get(0).proposedDiff().get("kind").asText()).isEqualTo("method-drop");
    assertThat(out.get(0).proposedDiff().get("stepIndex").asInt()).isEqualTo(1);
    assertThat(out.get(1).proposedDiff().get("kind").asText()).isEqualTo("method-collapse");
  }

  @Test
  void methodSimplification_picks_first_when_lengths_tie_not_last() {
    // Two equal-length steps: `len > longestLen` is strict, so the FIRST (index 0) wins.
    // A `>=` mutant would pick the last step (index 1) instead -> assertion fails.
    RecipeVersionDto v =
        version(List.of(ing("beef", false)), List.of(step(1, "abcde", 3), step(2, "fghij", 3)));
    List<AdaptationCandidateDto> out = new MethodSimplificationStrategy().generate(job, context(v));
    assertThat(out.get(0).proposedDiff().get("stepIndex").asInt()).isZero();
  }

  @Test
  void methodSimplification_null_instruction_counts_as_zero_length() {
    // First step null instruction (len 0), second has real text (len > 0): longest must be
    // index 1. Confirms the `instruction() == null ? 0 : length()` ternary.
    RecipeVersionDto v =
        version(List.of(ing("beef", false)), List.of(step(1, null, 0), step(2, "real text", 4)));
    List<AdaptationCandidateDto> out = new MethodSimplificationStrategy().generate(job, context(v));
    assertThat(out.get(0).proposedDiff().get("stepIndex").asInt()).isEqualTo(1);
  }

  // ---------------- IngredientRemoveStrategy ----------------

  @Test
  void ingredientRemove_null_version_yields_empty() {
    assertThat(new IngredientRemoveStrategy().generate(job, context(null))).isEmpty();
  }

  @Test
  void ingredientRemove_only_emits_for_optional_ingredients() {
    RecipeVersionDto v =
        version(
            List.of(ing("beef", false), ing("onion", true), ing("garlic", true)),
            List.of(step(1, "x", 1), step(2, "y", 1)));
    List<AdaptationCandidateDto> out = new IngredientRemoveStrategy().generate(job, context(v));
    // beef is NOT optional -> excluded; onion + garlic ARE -> 2 candidates, indices 0,1.
    assertThat(out).hasSize(2);
    assertThat(out).extracting(AdaptationCandidateDto::index).containsExactly(0, 1);
    assertThat(out.get(0).proposedDiff().get("key").asText()).isEqualTo("onion");
    assertThat(out.get(1).proposedDiff().get("key").asText()).isEqualTo("garlic");
  }

  @Test
  void ingredientRemove_no_optional_ingredients_yields_empty() {
    RecipeVersionDto v =
        version(
            List.of(ing("beef", false), ing("salt", false)),
            List.of(step(1, "x", 1), step(2, "y", 1)));
    assertThat(new IngredientRemoveStrategy().generate(job, context(v))).isEmpty();
  }

  // ---------------- IngredientSwapStrategy ----------------

  @Test
  void ingredientSwap_empty_ingredients_yields_empty() {
    RecipeVersionDto v = version(List.of(), List.of(step(1, "x", 1), step(2, "y", 1)));
    assertThat(new IngredientSwapStrategy().generate(job, context(v))).isEmpty();
  }

  @Test
  void ingredientSwap_unknown_key_is_skipped() {
    RecipeVersionDto v =
        version(List.of(ing("unobtainium", false)), List.of(step(1, "x", 1), step(2, "y", 1)));
    assertThat(new IngredientSwapStrategy().generate(job, context(v))).isEmpty();
  }

  @Test
  void ingredientSwap_emits_one_candidate_per_substitute_with_sequential_indices() {
    // "beef" -> [chicken, turkey, tofu] = 3 candidates, indices 0,1,2 (kills the
    // index++ -> index-- increment mutant: -1,-2,... would fail containsExactly).
    RecipeVersionDto v =
        version(List.of(ing("beef", false)), List.of(step(1, "x", 1), step(2, "y", 1)));
    List<AdaptationCandidateDto> out = new IngredientSwapStrategy().generate(job, context(v));
    assertThat(out).hasSize(3);
    assertThat(out).extracting(AdaptationCandidateDto::index).containsExactly(0, 1, 2);
    assertThat(out.get(0).proposedDiff().get("from").asText()).isEqualTo("beef");
    assertThat(out.get(0).proposedDiff().get("to").asText()).isEqualTo("chicken");
  }

  @Test
  void ingredientSwap_caps_at_top_three_ingredients() {
    // 4 swap-able ingredients but only the first 3 are considered (Math.min(3, size)).
    List<IngredientDto> ings =
        new ArrayList<>(
            List.of(
                ing("beef", false), // 3 subs
                ing("milk", false), // 2 subs
                ing("butter", false), // 2 subs
                ing("salt", false))); // would add 2 more but is index 3 -> skipped
    RecipeVersionDto v = version(ings, List.of(step(1, "x", 1), step(2, "y", 1)));
    List<AdaptationCandidateDto> out = new IngredientSwapStrategy().generate(job, context(v));
    // 3 + 2 + 2 = 7; salt's 2 are NOT included.
    assertThat(out).hasSize(7);
  }

  // ---------------- PortionAdjustStrategy ----------------

  @Test
  void portionAdjust_emits_exactly_four_factor_candidates() {
    List<AdaptationCandidateDto> out = new PortionAdjustStrategy().generate(job, context(null));
    assertThat(out).hasSize(4);
    assertThat(out).extracting(AdaptationCandidateDto::index).containsExactly(0, 1, 2, 3);
    assertThat(out.get(0).proposedDiff().get("factor").decimalValue()).isEqualByComparingTo("0.75");
    assertThat(out.get(3).proposedDiff().get("factor").decimalValue()).isEqualByComparingTo("1.5");
  }

  // ---------------- fixtures ----------------

  private static IngredientDto ing(String key, boolean optional) {
    return new IngredientDto(
        UUID.randomUUID(),
        0,
        key,
        key,
        BigDecimal.valueOf(100),
        "g",
        "diced",
        optional,
        false,
        BigDecimal.valueOf(0.9));
  }

  private static MethodStepDto step(int n, String instruction, int minutes) {
    return new MethodStepDto(UUID.randomUUID(), n, instruction, minutes);
  }

  private static RecipeVersionDto version(
      List<IngredientDto> ingredients, List<MethodStepDto> steps) {
    return new RecipeVersionDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        null,
        VersionTrigger.IMPORT,
        "initial",
        "pending",
        Instant.now(),
        "user:" + UUID.randomUUID(),
        null,
        ingredients,
        steps,
        new RecipeMetadataDto(2, 10, 20, 30, List.of("pot"), 3, 4, true, "italian", List.of("d")),
        new RecipeTagsDto("beef", "stovetop", null, List.of(), List.of()),
        null);
  }

  private static AdaptationContext context(RecipeVersionDto version) {
    return new AdaptationContext(
        "IMPORT",
        null,
        version,
        null,
        List.of(),
        null,
        "v0",
        null,
        new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
        null,
        null,
        null,
        null);
  }
}
