package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.AdaptationContextAssembler;
import com.example.mealprep.adaptation.ai.TriggerInputs;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdaptationContextAssemblerTest {

  private final RecipeQueryService recipeQuery = mock(RecipeQueryService.class);
  private final PreferenceQueryService prefQuery = mock(PreferenceQueryService.class);
  private final NutritionalKnowledgeService knowledge = mock(NutritionalKnowledgeService.class);
  private final AdaptationContextAssembler assembler =
      new AdaptationContextAssembler(recipeQuery, prefQuery, knowledge);

  @Test
  void feedback_inputs_populate_feedback_slice_and_mode() {
    AdaptationJob job = job(JobSource.FEEDBACK);
    stubEmptyDeps(job);

    TriggerInputs inputs = new TriggerInputs.FeedbackTriggerInputs("too salty", null);
    AdaptationContext ctx = assembler.assemble(job, List.of(), inputs);

    assertThat(ctx.mode()).isEqualTo("FEEDBACK");
    assertThat(ctx.feedbackText()).isEqualTo("too salty");
    assertThat(ctx.directive()).isNull();
    assertThat(ctx.dataModelChange()).isNull();
    assertThat(ctx.knowledgeBundle()).isNotNull();
  }

  @Test
  void import_inputs_leave_trigger_specific_fields_null() {
    AdaptationJob job = job(JobSource.IMPORT);
    stubEmptyDeps(job);

    AdaptationContext ctx =
        assembler.assemble(job, List.of(), new TriggerInputs.ImportTriggerInputs(null));

    assertThat(ctx.mode()).isEqualTo("IMPORT");
    assertThat(ctx.feedbackText()).isNull();
    assertThat(ctx.ratingDelta()).isNull();
    assertThat(ctx.directive()).isNull();
    assertThat(ctx.dataModelChange()).isNull();
  }

  @Test
  void plan_time_maps_to_plan_time_refine_mode() {
    AdaptationJob job = job(JobSource.PLAN_TIME);
    stubEmptyDeps(job);

    AdaptationContext ctx =
        assembler.assemble(job, List.of(), new TriggerInputs.PlanTimeTriggerInputs(null, null));

    assertThat(ctx.mode()).isEqualTo("PLAN_TIME_REFINE");
  }

  @Test
  void data_model_change_carries_summary_node() {
    AdaptationJob job = job(JobSource.DATA_MODEL_CHANGE);
    stubEmptyDeps(job);
    var summary = JsonNodeFactory.instance.objectNode().put("changedField", "x");

    AdaptationContext ctx =
        assembler.assemble(job, List.of(), new TriggerInputs.DataModelTriggerInputs(null, summary));

    assertThat(ctx.mode()).isEqualTo("DATA_MODEL_CHANGE");
    assertThat(ctx.dataModelChange()).isEqualTo(summary);
  }

  @Test
  void missing_recipe_yields_null_recipe_but_non_null_bundle_and_hash() {
    AdaptationJob job = job(JobSource.FEEDBACK);
    when(recipeQuery.getById(job.getRecipeId())).thenReturn(Optional.empty());
    when(prefQuery.getHardConstraints(job.getUserId())).thenReturn(Optional.empty());
    when(knowledge.lookupForRecipe(any(), anyList()))
        .thenReturn(new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()));

    AdaptationContext ctx =
        assembler.assemble(job, List.of(), new TriggerInputs.FeedbackTriggerInputs("x", null));

    assertThat(ctx.recipe()).isNull();
    assertThat(ctx.currentVersion()).isNull();
    assertThat(ctx.knowledgeBundle()).isNotNull();
    assertThat(ctx.hardConstraintsHash()).isEqualTo("hc:none");
  }

  @Test
  void assemble_loads_fingerprint_when_branch_present_and_copies_candidates() {
    AdaptationJob job = job(JobSource.IMPORT);
    UUID branchId = UUID.randomUUID();
    var version = version(List.of(ing("beef")));
    RecipeDto recipe =
        new RecipeDto(
            job.getRecipeId(),
            job.getUserId(),
            com.example.mealprep.recipe.domain.entity.Catalogue.USER,
            "R",
            "d",
            1,
            branchId,
            com.example.mealprep.recipe.domain.entity.DataQuality.AI_GENERATED,
            com.example.mealprep.recipe.domain.entity.NutritionStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0L,
            Instant.now(),
            Instant.now(),
            version,
            List.of());
    var fp =
        new com.example.mealprep.recipe.api.dto.CharacterFingerprintDto(
            List.of("beef"),
            List.of(),
            List.of(),
            List.of(),
            com.example.mealprep.recipe.domain.entity.Complexity.MODERATE,
            "italian");
    when(recipeQuery.getById(job.getRecipeId())).thenReturn(Optional.of(recipe));
    when(recipeQuery.getFingerprint(job.getRecipeId(), branchId)).thenReturn(Optional.of(fp));
    when(prefQuery.getHardConstraints(job.getUserId())).thenReturn(Optional.empty());
    when(knowledge.lookupForRecipe(any(), anyList()))
        .thenReturn(new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()));

    // null triggerInputs -> all trigger-specific fields null (kills L90-93 ternary mutants);
    // null candidates -> List.of() (kills the L85 ternary).
    AdaptationContext ctx = assembler.assemble(job, null, null);

    assertThat(ctx.fingerprint()).isSameAs(fp);
    assertThat(ctx.currentVersion()).isSameAs(version);
    assertThat(ctx.candidates()).isEmpty();
    assertThat(ctx.feedbackText()).isNull();
    assertThat(ctx.ratingDelta()).isNull();
    assertThat(ctx.directive()).isNull();
    assertThat(ctx.dataModelChange()).isNull();
  }

  @Test
  void assemble_skips_fingerprint_when_branch_id_null() {
    AdaptationJob job = job(JobSource.IMPORT);
    RecipeDto recipe =
        new RecipeDto(
            job.getRecipeId(),
            job.getUserId(),
            com.example.mealprep.recipe.domain.entity.Catalogue.USER,
            "R",
            "d",
            1,
            null, // currentBranchId null -> fingerprint lookup skipped
            com.example.mealprep.recipe.domain.entity.DataQuality.AI_GENERATED,
            com.example.mealprep.recipe.domain.entity.NutritionStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0L,
            Instant.now(),
            Instant.now(),
            version(List.of(ing("beef"))),
            List.of());
    when(recipeQuery.getById(job.getRecipeId())).thenReturn(Optional.of(recipe));
    when(prefQuery.getHardConstraints(job.getUserId())).thenReturn(Optional.empty());
    when(knowledge.lookupForRecipe(any(), anyList()))
        .thenReturn(new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()));

    AdaptationContext ctx =
        assembler.assemble(job, List.of(), new TriggerInputs.ImportTriggerInputs(null));

    assertThat(ctx.fingerprint()).isNull();
    org.mockito.Mockito.verify(recipeQuery, org.mockito.Mockito.never())
        .getFingerprint(any(), any());
  }

  private static com.example.mealprep.recipe.api.dto.IngredientDto ing(String mappingKey) {
    return new com.example.mealprep.recipe.api.dto.IngredientDto(
        UUID.randomUUID(),
        0,
        mappingKey,
        "display",
        java.math.BigDecimal.valueOf(100),
        "g",
        "diced",
        false,
        false,
        java.math.BigDecimal.valueOf(0.9));
  }

  private static com.example.mealprep.recipe.api.dto.RecipeVersionDto version(
      List<com.example.mealprep.recipe.api.dto.IngredientDto> ingredients) {
    return new com.example.mealprep.recipe.api.dto.RecipeVersionDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        null,
        com.example.mealprep.recipe.domain.entity.VersionTrigger.IMPORT,
        "initial",
        "pending",
        Instant.now(),
        "user:" + UUID.randomUUID(),
        null,
        ingredients,
        List.of(),
        new com.example.mealprep.recipe.api.dto.RecipeMetadataDto(
            2, 10, 20, 30, List.of("pot"), 3, 4, true, "italian", List.of("d")),
        new com.example.mealprep.recipe.api.dto.RecipeTagsDto(
            "beef", "stovetop", null, List.of(), List.of()),
        null);
  }

  private void stubEmptyDeps(AdaptationJob job) {
    RecipeDto recipe =
        new RecipeDto(
            job.getRecipeId(),
            job.getUserId(),
            com.example.mealprep.recipe.domain.entity.Catalogue.USER,
            "Test Recipe",
            "desc",
            1,
            UUID.randomUUID(),
            com.example.mealprep.recipe.domain.entity.DataQuality.AI_GENERATED,
            com.example.mealprep.recipe.domain.entity.NutritionStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0L,
            Instant.now(),
            Instant.now(),
            null,
            List.of());
    when(recipeQuery.getById(job.getRecipeId())).thenReturn(Optional.of(recipe));
    when(recipeQuery.getFingerprint(eq(job.getRecipeId()), any())).thenReturn(Optional.empty());
    when(prefQuery.getHardConstraints(job.getUserId())).thenReturn(Optional.empty());
    when(knowledge.lookupForRecipe(any(), anyList()))
        .thenReturn(new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()));
  }

  private static AdaptationJob job(JobSource source) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(com.example.mealprep.recipe.domain.entity.Catalogue.USER)
        .source(source)
        .priority(JobPriority.SYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(JobStatus.RUNNING)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }
}
