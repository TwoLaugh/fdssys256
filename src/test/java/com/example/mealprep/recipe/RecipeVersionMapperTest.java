package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.mapper.IngredientMapper;
import com.example.mealprep.recipe.api.mapper.MethodStepMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMetadataMapper;
import com.example.mealprep.recipe.api.mapper.RecipeTagsMapper;
import com.example.mealprep.recipe.api.mapper.RecipeVersionMapper;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link RecipeVersionMapper}: the null-entity guards on both {@code toDto}
 * and {@code toOverlayDto}, the branch-null projection, the {@code Boolean.TRUE.equals} optional
 * mapping, and the null overlaid-list guards. Real sibling mappers (no mocking within module).
 */
class RecipeVersionMapperTest {

  private final RecipeVersionMapper mapper =
      new RecipeVersionMapper(
          new IngredientMapper(),
          new MethodStepMapper(),
          new RecipeMetadataMapper(),
          new RecipeTagsMapper());

  @Test
  void toDto_nullEntity_returnsNull() {
    assertThat(mapper.toDto(null)).isNull();
  }

  @Test
  void toDto_nullEntityWithSubs_returnsNull() {
    assertThat(mapper.toDto(null, List.of(UUID.randomUUID()))).isNull();
  }

  @Test
  void toDto_singleArg_setsAppliedSubsNull_andCopiesScalars() {
    UUID branchId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    RecipeVersion v = bareVersion(branchId);
    v.setParentVersionId(parentId);
    v.setTrigger(VersionTrigger.MANUAL_CREATE);
    v.setChangeReason("first version");
    v.setEmbeddingStatus("pending");
    v.setCreatedByActor("user:abc");
    v.setAdapterTraceId(traceId);

    RecipeVersionDto dto = mapper.toDto(v);

    assertThat(dto.id()).isEqualTo(v.getId());
    assertThat(dto.branchId()).isEqualTo(branchId);
    assertThat(dto.versionNumber()).isEqualTo(1);
    assertThat(dto.parentVersionId()).isEqualTo(parentId);
    assertThat(dto.trigger()).isEqualTo(VersionTrigger.MANUAL_CREATE);
    assertThat(dto.changeReason()).isEqualTo("first version");
    assertThat(dto.embeddingStatus()).isEqualTo("pending");
    assertThat(dto.createdByActor()).isEqualTo("user:abc");
    assertThat(dto.adapterTraceId()).isEqualTo(traceId);
    assertThat(dto.appliedSubstitutionIds()).isNull();
    assertThat(dto.ingredients()).isEmpty();
    assertThat(dto.methodSteps()).isEmpty();
  }

  @Test
  void toDto_nullBranch_yieldsNullBranchId() {
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setBranch(null);

    assertThat(mapper.toDto(v).branchId()).isNull();
  }

  @Test
  void toDto_appliedSubsPropagated() {
    List<UUID> subs = List.of(UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion v = bareVersion(UUID.randomUUID());

    assertThat(mapper.toDto(v, subs).appliedSubstitutionIds()).isEqualTo(subs);
  }

  @Test
  void toDto_carriesPersistedEmbedding() {
    RecipeVersion v = bareVersion(UUID.randomUUID());
    float[] embedding = {0.1f, 0.2f, 0.3f};
    v.setEmbedding(embedding);

    assertThat(mapper.toDto(v).embedding()).containsExactly(0.1f, 0.2f, 0.3f);
  }

  @Test
  void toDto_nullEmbedding_yieldsNull() {
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setEmbedding(null);

    assertThat(mapper.toDto(v).embedding()).isNull();
  }

  @Test
  void toOverlayDto_carriesBaseVersionEmbedding() {
    RecipeVersion base = bareVersion(UUID.randomUUID());
    base.setEmbedding(new float[] {0.5f, 0.5f});

    RecipeVersionDto dto = mapper.toOverlayDto(base, List.of(), List.of(), List.of());

    assertThat(dto.embedding()).containsExactly(0.5f, 0.5f);
  }

  // ---------------- toOverlayDto ----------------

  @Test
  void toOverlayDto_nullBaseVersion_returnsNull() {
    assertThat(mapper.toOverlayDto(null, List.of(), List.of(), List.of())).isNull();
  }

  @Test
  void toOverlayDto_mapsOverlaidIngredients_optionalTrueEquality() {
    UUID branchId = UUID.randomUUID();
    RecipeVersion base = bareVersion(branchId);
    List<CreateIngredientRequest> overlaid =
        List.of(
            new CreateIngredientRequest(
                0, "soy.crumble", "Soy crumble", new BigDecimal("400.000"), "g", "diced", true),
            new CreateIngredientRequest(1, "salt", "Salt", null, null, null, null));
    List<CreateMethodStepRequest> method =
        List.of(new CreateMethodStepRequest(1, "Brown the crumble.", 5));
    List<UUID> subs = List.of(UUID.randomUUID());

    RecipeVersionDto dto = mapper.toOverlayDto(base, overlaid, method, subs);

    assertThat(dto.id()).isEqualTo(base.getId());
    assertThat(dto.branchId()).isEqualTo(branchId);
    assertThat(dto.ingredients()).hasSize(2);
    assertThat(dto.ingredients().get(0).displayName()).isEqualTo("Soy crumble");
    assertThat(dto.ingredients().get(0).optional()).isTrue();
    // null optional must map to false via Boolean.TRUE.equals.
    assertThat(dto.ingredients().get(1).optional()).isFalse();
    assertThat(dto.ingredients().get(0).id()).isNull();
    assertThat(dto.ingredients().get(0).needsReview()).isFalse();
    assertThat(dto.methodSteps()).hasSize(1);
    assertThat(dto.methodSteps().get(0).stepNumber()).isEqualTo(1);
    assertThat(dto.methodSteps().get(0).instruction()).isEqualTo("Brown the crumble.");
    assertThat(dto.methodSteps().get(0).durationMinutes()).isEqualTo(5);
    assertThat(dto.appliedSubstitutionIds()).isEqualTo(subs);
  }

  @Test
  void toOverlayDto_optionalFalse_mapsToFalse() {
    RecipeVersion base = bareVersion(UUID.randomUUID());
    List<CreateIngredientRequest> overlaid =
        List.of(new CreateIngredientRequest(0, "k", "d", null, null, null, false));

    RecipeVersionDto dto = mapper.toOverlayDto(base, overlaid, List.of(), List.of());

    assertThat(dto.ingredients().get(0).optional()).isFalse();
  }

  @Test
  void toOverlayDto_nullOverlaidLists_yieldEmptyCollections() {
    RecipeVersion base = bareVersion(UUID.randomUUID());

    RecipeVersionDto dto = mapper.toOverlayDto(base, null, null, null);

    assertThat(dto.ingredients()).isEmpty();
    assertThat(dto.methodSteps()).isEmpty();
    assertThat(dto.appliedSubstitutionIds()).isNull();
  }

  @Test
  void toOverlayDto_nullBranch_yieldsNullBranchId() {
    RecipeVersion base = bareVersion(UUID.randomUUID());
    base.setBranch(null);

    assertThat(mapper.toOverlayDto(base, List.of(), List.of(), List.of()).branchId()).isNull();
  }

  // ---------------- nutritionPerServing (recipe-version-nutrition-per-serving) ----------------

  @Test
  void toDto_noPersistedNutrition_yieldsNull() {
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setNutritionPerServing(null);

    assertThat(mapper.toDto(v).nutritionPerServing()).isNull();
  }

  @Test
  void toDto_calculatedNutrition_mapsAllFigures() {
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setNutritionPerServing(storedResult("calculated"));

    NutritionPerServingDto dto = mapper.toDto(v).nutritionPerServing();

    assertThat(dto).isNotNull();
    assertThat(dto.calories()).isEqualTo(520);
    assertThat(dto.proteinG()).isEqualByComparingTo("38.5");
    assertThat(dto.carbsG()).isEqualByComparingTo("61.2");
    assertThat(dto.fatG()).isEqualByComparingTo("14.8");
    assertThat(dto.fibreG()).isEqualByComparingTo("6.1");
    assertThat(dto.micros()).hasSize(2);
    assertThat(dto.micros().get("iron_mg")).isEqualByComparingTo("2.5");
    assertThat(dto.micros().get("vitamin_c_mg")).isEqualByComparingTo("12.0");
  }

  @Test
  void toDto_partialNutrition_stillSurfacesFigures() {
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setNutritionPerServing(storedResult("partial"));

    NutritionPerServingDto dto = mapper.toDto(v).nutritionPerServing();

    assertThat(dto).isNotNull();
    assertThat(dto.calories()).isEqualTo(520);
  }

  @Test
  void toDto_pendingStoredResult_yieldsNull() {
    // A stored result whose own status is still pending (no ingredient resolved) carries no
    // meaningful figures — the contract field stays null per the ticket.
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setNutritionPerServing(storedResult("pending"));

    assertThat(mapper.toDto(v).nutritionPerServing()).isNull();
  }

  @Test
  void toDto_missingNumericFields_defaultToZero_andMicrosEmpty() {
    ObjectNode sparse = JsonNodeFactory.instance.objectNode();
    sparse.put("nutritionStatus", "calculated");
    sparse.put("caloriesPerServing", 100);
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setNutritionPerServing(sparse);

    NutritionPerServingDto dto = mapper.toDto(v).nutritionPerServing();

    assertThat(dto).isNotNull();
    assertThat(dto.calories()).isEqualTo(100);
    assertThat(dto.proteinG()).isEqualByComparingTo("0");
    assertThat(dto.carbsG()).isEqualByComparingTo("0");
    assertThat(dto.fatG()).isEqualByComparingTo("0");
    assertThat(dto.fibreG()).isEqualByComparingTo("0");
    assertThat(dto.micros()).isEmpty();
  }

  @Test
  void toDto_nonObjectStoredJson_yieldsNull() {
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setNutritionPerServing(JsonNodeFactory.instance.textNode("garbage"));

    assertThat(mapper.toDto(v).nutritionPerServing()).isNull();
  }

  @Test
  void toOverlayDto_carriesBaseVersionNutrition() {
    RecipeVersion base = bareVersion(UUID.randomUUID());
    base.setNutritionPerServing(storedResult("calculated"));

    RecipeVersionDto dto = mapper.toOverlayDto(base, List.of(), List.of(), List.of());

    assertThat(dto.nutritionPerServing()).isNotNull();
    assertThat(dto.nutritionPerServing().calories()).isEqualTo(520);
  }

  /**
   * The persisted shape is the nutrition module's {@code RecipeNutritionResultDto} written verbatim
   * by the recipe-01g writer bridge (see {@code RecipeServiceImpl#updateNutritionStatus}).
   */
  private static ObjectNode storedResult(String status) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("recipeId", UUID.randomUUID().toString());
    node.put("caloriesPerServing", 520);
    node.put("proteinPerServingG", new BigDecimal("38.5"));
    node.put("carbsPerServingG", new BigDecimal("61.2"));
    node.put("fatPerServingG", new BigDecimal("14.8"));
    node.put("fibrePerServingG", new BigDecimal("6.1"));
    ObjectNode micros = node.putObject("microsPerServing");
    micros.put("iron_mg", new BigDecimal("2.5"));
    micros.put("vitamin_c_mg", new BigDecimal("12.0"));
    node.put("nutritionStatus", status);
    node.putArray("unmapped");
    return node;
  }

  // ---------------- helpers ----------------

  private static RecipeVersion bareVersion(UUID branchId) {
    return RecipeVersion.builder()
        .id(UUID.randomUUID())
        .branch(RecipeBranch.builder().id(branchId).name("main").build())
        .versionNumber(1)
        .trigger(VersionTrigger.MANUAL_CREATE)
        .embeddingStatus("pending")
        .createdByActor("user:test")
        .ingredients(new ArrayList<>())
        .methodSteps(new ArrayList<>())
        .build();
  }
}
