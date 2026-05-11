package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
import com.example.mealprep.recipe.domain.entity.MethodOverlayLine;
import com.example.mealprep.recipe.domain.entity.RecipeSubstitution;
import com.example.mealprep.recipe.domain.service.internal.NewVersionInput;
import com.example.mealprep.recipe.domain.service.internal.SubstitutionOverlayApplier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubstitutionOverlayApplier} per ticket recipe-01e edge-case checklist:
 * identical overlay; single swap; method overlay at named step; method overlay at non-existent
 * step.
 */
class SubstitutionOverlayApplierTest {

  private final SubstitutionOverlayApplier applier = new SubstitutionOverlayApplier();

  private static NewVersionInput sampleBody() {
    List<CreateIngredientRequest> ings =
        List.of(
            new CreateIngredientRequest(
                0, "spaghetti.dry", "Spaghetti", new BigDecimal("400.000"), "g", null, false),
            new CreateIngredientRequest(
                1, "beef.mince", "Lean beef mince", new BigDecimal("500.000"), "g", null, false));
    List<CreateMethodStepRequest> method =
        List.of(
            new CreateMethodStepRequest(1, "Brown the mince in a wide pan.", 8),
            new CreateMethodStepRequest(2, "Add passata and simmer for 25 minutes.", 25));
    CreateRecipeMetadataRequest meta =
        new CreateRecipeMetadataRequest(
            4, 15, 30, 45, List.of("pan"), 3, 2, true, "Italian", List.of("DINNER"));
    CreateRecipeTagsRequest tags =
        new CreateRecipeTagsRequest("beef", "stovetop", null, List.of("savoury"), List.of());
    return new NewVersionInput(ings, method, meta, tags);
  }

  private static RecipeSubstitution sub(
      String origKey,
      String subKey,
      BigDecimal subQty,
      String subUnit,
      List<MethodOverlayLine> overlay,
      Instant createdAt) {
    return RecipeSubstitution.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .versionId(UUID.randomUUID())
        .branchId(UUID.randomUUID())
        .originalMappingKey(origKey)
        .originalQuantity(new BigDecimal("1.000"))
        .originalUnit("g")
        .substituteMappingKey(subKey)
        .substituteQuantity(subQty)
        .substituteUnit(subUnit)
        .reason(SubstitutionReason.DIETARY_TEMP)
        .methodOverlay(overlay)
        .temporary(true)
        .applicationCount(0)
        .state(SubstitutionState.ACCEPTED)
        .createdAt(createdAt)
        .createdByActor("user:test")
        .build();
  }

  @Test
  void emptySubs_returnsBaseBodyCopy() {
    NewVersionInput base = sampleBody();
    NewVersionInput result = applier.apply(base, List.of());
    assertThat(result.ingredients()).hasSize(2);
    assertThat(result.ingredients().get(1).ingredientMappingKey()).isEqualTo("beef.mince");
  }

  @Test
  void singleSwap_replacesMatchingIngredient() {
    NewVersionInput base = sampleBody();
    RecipeSubstitution s =
        sub(
            "beef.mince",
            "soy.crumble",
            new BigDecimal("400.000"),
            "g",
            null,
            Instant.parse("2026-05-01T10:00:00Z"));
    NewVersionInput result = applier.apply(base, List.of(s));
    assertThat(result.ingredients()).hasSize(2);
    assertThat(result.ingredients().get(0).ingredientMappingKey()).isEqualTo("spaghetti.dry");
    assertThat(result.ingredients().get(1).ingredientMappingKey()).isEqualTo("soy.crumble");
    assertThat(result.ingredients().get(1).quantity()).isEqualByComparingTo("400.000");
  }

  @Test
  void identicalOverlay_noChange() {
    NewVersionInput base = sampleBody();
    RecipeSubstitution s =
        sub(
            "non-existent.key",
            "other.key",
            new BigDecimal("100.000"),
            "g",
            null,
            Instant.parse("2026-05-01T10:00:00Z"));
    NewVersionInput result = applier.apply(base, List.of(s));
    assertThat(result.ingredients())
        .extracting(CreateIngredientRequest::ingredientMappingKey)
        .containsExactly("spaghetti.dry", "beef.mince");
  }

  @Test
  void methodOverlay_replacesAtNamedStep() {
    NewVersionInput base = sampleBody();
    RecipeSubstitution s =
        sub(
            "beef.mince",
            "soy.crumble",
            new BigDecimal("400.000"),
            "g",
            List.of(new MethodOverlayLine(2, "Simmer for only 15 minutes (soy cooks faster).")),
            Instant.parse("2026-05-01T10:00:00Z"));
    NewVersionInput result = applier.apply(base, List.of(s));
    assertThat(result.method()).hasSize(2);
    assertThat(result.method().get(1).instruction())
        .isEqualTo("Simmer for only 15 minutes (soy cooks faster).");
    assertThat(result.method().get(0).instruction()).isEqualTo("Brown the mince in a wide pan.");
  }

  @Test
  void methodOverlay_atNonExistentStep_isIgnored() {
    NewVersionInput base = sampleBody();
    RecipeSubstitution s =
        sub(
            "beef.mince",
            "soy.crumble",
            new BigDecimal("400.000"),
            "g",
            List.of(new MethodOverlayLine(99, "Should not appear.")),
            Instant.parse("2026-05-01T10:00:00Z"));
    NewVersionInput result = applier.apply(base, List.of(s));
    assertThat(result.method()).hasSize(2);
    assertThat(result.method().get(0).instruction()).isEqualTo("Brown the mince in a wide pan.");
    assertThat(result.method().get(1).instruction())
        .isEqualTo("Add passata and simmer for 25 minutes.");
  }

  @Test
  void multipleSubs_appliedInCreatedAtOrder() {
    NewVersionInput base = sampleBody();
    RecipeSubstitution earlier =
        sub(
            "beef.mince",
            "intermediate.key",
            new BigDecimal("450.000"),
            "g",
            null,
            Instant.parse("2026-05-01T09:00:00Z"));
    RecipeSubstitution later =
        sub(
            "beef.mince",
            "soy.crumble",
            new BigDecimal("400.000"),
            "g",
            null,
            Instant.parse("2026-05-01T10:00:00Z"));
    NewVersionInput result = applier.apply(base, List.of(later, earlier));
    // The earlier sub runs first, swapping beef.mince -> intermediate.key, so the later sub does
    // not match. The final beef-line keys are: spaghetti.dry, intermediate.key.
    assertThat(result.ingredients().get(1).ingredientMappingKey()).isEqualTo("intermediate.key");
  }
}
