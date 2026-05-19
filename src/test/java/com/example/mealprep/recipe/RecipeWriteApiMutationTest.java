package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.mapper.IngredientMapper;
import com.example.mealprep.recipe.api.mapper.MethodStepMapper;
import com.example.mealprep.recipe.api.mapper.ParsedRecipeToCreateRequestMapper;
import com.example.mealprep.recipe.api.mapper.RecipeBranchMapper;
import com.example.mealprep.recipe.api.mapper.RecipeDiffMapper;
import com.example.mealprep.recipe.api.mapper.RecipeImportMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMetadataMapper;
import com.example.mealprep.recipe.api.mapper.RecipeSubstitutionMapper;
import com.example.mealprep.recipe.api.mapper.RecipeTagsMapper;
import com.example.mealprep.recipe.api.mapper.RecipeVersionMapper;
import com.example.mealprep.recipe.config.UrlFetcher;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.repository.RecipeBranchRepository;
import com.example.mealprep.recipe.domain.repository.RecipeImportRepository;
import com.example.mealprep.recipe.domain.repository.RecipeIngredientRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeSubstitutionRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.internal.DivergenceScoreCalculator;
import com.example.mealprep.recipe.domain.service.internal.FingerprintDeriver;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser;
import com.example.mealprep.recipe.domain.service.internal.RecipeServiceImpl;
import com.example.mealprep.recipe.domain.service.internal.SubstitutionOverlayApplier;
import com.example.mealprep.recipe.domain.service.internal.VersionDiffer;
import com.example.mealprep.recipe.exception.RecipeBranchNotFoundException;
import com.example.mealprep.recipe.exception.RecipeBranchPointInvalidException;
import com.example.mealprep.recipe.spi.SaveAdaptedBranchCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Mutation-killing unit tests for the adaptation-pipeline write SPI ({@code saveAdaptedVersion} /
 * {@code saveAdaptedBranch}) on {@link RecipeServiceImpl}. The happy-path tests in {@link
 * RecipeWriteApiTest} did not assert the persisted version body, so the {@code
 * populateIngredients/MethodSteps/setMetadata/setTags/setNutritionStatus/setCurrentVersion}
 * void-call-removal mutants and the ownership-filter boolean mutants survived. These tests capture
 * the saved entities and assert their full shape.
 */
@ExtendWith(MockitoExtension.class)
class RecipeWriteApiMutationTest {

  @Mock private RecipeRepository recipeRepository;
  @Mock private RecipeBranchRepository branchRepository;
  @Mock private RecipeVersionRepository versionRepository;
  @Mock private RecipeImportRepository importRepository;
  @Mock private RecipeIngredientRepository ingredientRepository;
  @Mock private RecipeSubstitutionRepository substitutionRepository;
  @Mock private UrlFetcher urlFetcher;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final IngredientMapper ingredientMapper = new IngredientMapper();
  private final MethodStepMapper methodStepMapper = new MethodStepMapper();
  private final RecipeMetadataMapper metadataMapper = new RecipeMetadataMapper();
  private final RecipeTagsMapper tagsMapper = new RecipeTagsMapper();
  private final RecipeVersionMapper versionMapper =
      new RecipeVersionMapper(ingredientMapper, methodStepMapper, metadataMapper, tagsMapper);
  private final RecipeMapper recipeMapper = new RecipeMapper();
  private final RecipeBranchMapper branchMapper = new RecipeBranchMapper();
  private final RecipeImportMapper importMapper = new RecipeImportMapper();
  private final RecipeDiffMapper diffMapper = new RecipeDiffMapper();
  private final RecipeSubstitutionMapper substitutionMapper = new RecipeSubstitutionMapper();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HtmlImportParser htmlImportParser = new HtmlImportParser(objectMapper);
  private final VersionDiffer versionDiffer = new VersionDiffer(objectMapper);
  private final DivergenceScoreCalculator divergenceCalculator = new DivergenceScoreCalculator();
  private final FingerprintDeriver fingerprintDeriver = new FingerprintDeriver();
  private final SubstitutionOverlayApplier overlayApplier = new SubstitutionOverlayApplier();
  private final Clock fixedClock = Clock.fixed(Instant.now().minusSeconds(7200), ZoneOffset.UTC);

  private RecipeServiceImpl service() {
    return new RecipeServiceImpl(
        recipeRepository,
        branchRepository,
        versionRepository,
        importRepository,
        ingredientRepository,
        substitutionRepository,
        recipeMapper,
        versionMapper,
        branchMapper,
        importMapper,
        diffMapper,
        substitutionMapper,
        urlFetcher,
        htmlImportParser,
        new ParsedRecipeToCreateRequestMapper(),
        versionDiffer,
        divergenceCalculator,
        fingerprintDeriver,
        overlayApplier,
        objectMapper,
        eventPublisher,
        fixedClock);
  }

  // ---------------- saveAdaptedVersion ----------------

  @Test
  void saveAdaptedVersion_persistsFullBody_bumpsBothHeads_andResetsNutrition() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID parentVersionId = UUID.randomUUID();
    UUID adapterTraceId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, branchId, 3);
    recipe.setNutritionStatus(NutritionStatus.CALCULATED);
    RecipeBranch branch = branchAtVersion(branchId, recipe, 3);
    when(recipeRepository.findByIdForUpdate(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findCurrentVersionId(recipeId, branchId, 3))
        .thenReturn(Optional.of(parentVersionId));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.saveAndFlush(any(RecipeBranch.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("marker", "carried-through");
    SaveAdaptedVersionCommand base =
        RecipeTestData.defaultSaveAdaptedVersionCommand(
            recipeId, branchId, 3, parentVersionId, adapterTraceId);
    SaveAdaptedVersionCommand cmd =
        new SaveAdaptedVersionCommand(
            base.recipeId(),
            base.branchId(),
            base.expectedParentVersionNumber(),
            base.expectedParentVersionId(),
            base.ingredients(),
            base.method(),
            base.metadata(),
            base.tags(),
            base.characterFingerprint(),
            diff,
            base.changeReason(),
            base.adapterTraceId());

    var dto = service().saveAdaptedVersion(cmd);

    // DTO body proves populateIngredients/MethodSteps/setMetadata/setTags ran (kills the
    // L1412-L1415 void-call-removal mutants — a removed call leaves an empty/null section).
    assertThat(dto.ingredients()).hasSize(3);
    assertThat(dto.ingredients().get(1).ingredientMappingKey()).isEqualTo("beef.mince");
    assertThat(dto.methodSteps()).hasSize(3);
    assertThat(dto.metadata()).isNotNull();
    assertThat(dto.metadata().servings()).isEqualTo(4);
    assertThat(dto.tags()).isNotNull();
    assertThat(dto.tags().protein()).isEqualTo("beef");

    ArgumentCaptor<RecipeVersion> vCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(vCaptor.capture());
    RecipeVersion saved = vCaptor.getValue();
    assertThat(saved.getIngredients()).hasSize(3);
    assertThat(saved.getMethodSteps()).hasSize(3);
    assertThat(saved.getMetadata()).isNotNull();
    assertThat(saved.getTags()).isNotNull();
    // cmd.changeDiff() != null → that exact diff must be carried (kills the L1400 negate which
    // would substitute an empty object node).
    assertThat(saved.getChangeDiff().path("marker").asText()).isEqualTo("carried-through");

    // Head bump on both recipe and branch (kills the L1419 / L1425 setCurrentVersion removals)
    // plus the nutrition reset (kills the L1422 setNutritionStatus removal).
    assertThat(recipe.getCurrentVersion()).isEqualTo(4);
    assertThat(branch.getCurrentVersion()).isEqualTo(4);
    assertThat(recipe.getNutritionStatus()).isEqualTo(NutritionStatus.PENDING);
  }

  @Test
  void saveAdaptedVersion_nullChangeDiff_fallsBackToEmptyObjectNode() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID parentVersionId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, branchId, 1);
    RecipeBranch branch = branchAtVersion(branchId, recipe, 1);
    when(recipeRepository.findByIdForUpdate(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findCurrentVersionId(recipeId, branchId, 1))
        .thenReturn(Optional.of(parentVersionId));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.saveAndFlush(any(RecipeBranch.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SaveAdaptedVersionCommand base =
        RecipeTestData.defaultSaveAdaptedVersionCommand(
            recipeId, branchId, 1, parentVersionId, UUID.randomUUID());
    SaveAdaptedVersionCommand cmd =
        new SaveAdaptedVersionCommand(
            base.recipeId(),
            base.branchId(),
            base.expectedParentVersionNumber(),
            base.expectedParentVersionId(),
            base.ingredients(),
            base.method(),
            base.metadata(),
            base.tags(),
            base.characterFingerprint(),
            null,
            base.changeReason(),
            base.adapterTraceId());

    service().saveAdaptedVersion(cmd);

    ArgumentCaptor<RecipeVersion> vCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(vCaptor.capture());
    // null diff → an empty (but non-null, no "marker") object node.
    assertThat(vCaptor.getValue().getChangeDiff()).isNotNull();
    assertThat(vCaptor.getValue().getChangeDiff().isObject()).isTrue();
    assertThat(vCaptor.getValue().getChangeDiff().size()).isZero();
  }

  @Test
  void saveAdaptedVersion_branchBelongsToDifferentRecipe_throwsBranchNotFound() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, branchId, 1);
    Recipe otherRecipe = recipeAtVersion(UUID.randomUUID(), UUID.randomUUID(), 1);
    RecipeBranch foreignBranch = branchAtVersion(branchId, otherRecipe, 1);
    when(recipeRepository.findByIdForUpdate(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(foreignBranch));

    SaveAdaptedVersionCommand cmd =
        RecipeTestData.defaultSaveAdaptedVersionCommand(
            recipeId, branchId, 1, UUID.randomUUID(), UUID.randomUUID());

    // The `.filter(b -> b.getRecipe()...getId().equals(recipe.getId()))` must reject a branch
    // owned by another recipe (kills the L1355 "replaced boolean return with true" mutant which
    // would accept the foreign branch).
    assertThatThrownBy(() -> service().saveAdaptedVersion(cmd))
        .isInstanceOf(RecipeBranchNotFoundException.class);
  }

  // ---------------- saveAdaptedBranch ----------------

  @Test
  void saveAdaptedBranch_persistsFullV1Body_andDoesNotBumpRecipeHead() {
    UUID recipeId = UUID.randomUUID();
    UUID parentBranchId = UUID.randomUUID();
    UUID branchPointVersionId = UUID.randomUUID();
    UUID adapterTraceId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, parentBranchId, 5);
    RecipeVersion bp = versionRow(branchPointVersionId, recipe, 5);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findByRecipeIdAndName(recipeId, "adapter-low-sodium"))
        .thenReturn(Optional.empty());
    when(versionRepository.findById(branchPointVersionId)).thenReturn(Optional.of(bp));
    when(branchRepository.saveAndFlush(any(RecipeBranch.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SaveAdaptedBranchCommand cmd =
        RecipeTestData.defaultSaveAdaptedBranchCommand(
            recipeId, parentBranchId, branchPointVersionId, adapterTraceId);

    var dto = service().saveAdaptedBranch(cmd);

    assertThat(dto.name()).isEqualTo("adapter-low-sodium");

    ArgumentCaptor<RecipeVersion> vCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(vCaptor.capture());
    RecipeVersion savedV1 = vCaptor.getValue();
    // Kills the L1526-L1529 void-call-removal mutants on the v1 body.
    assertThat(savedV1.getIngredients()).hasSize(3);
    assertThat(savedV1.getMethodSteps()).hasSize(3);
    assertThat(savedV1.getMetadata()).isNotNull();
    assertThat(savedV1.getMetadata().getServings()).isEqualTo(4);
    assertThat(savedV1.getTags()).isNotNull();
    assertThat(savedV1.getTags().getProtein()).isEqualTo("beef");
    assertThat(savedV1.getVersionNumber()).isEqualTo(1);
    assertThat(savedV1.getCreatedByActor()).isEqualTo("pipeline:" + adapterTraceId);

    // Branch creation must NOT bump recipe head.
    assertThat(recipe.getCurrentVersion()).isEqualTo(5);
  }

  @Test
  void saveAdaptedBranch_branchPointVersionBelongsToDifferentRecipe_throwsInvalid() {
    UUID recipeId = UUID.randomUUID();
    UUID parentBranchId = UUID.randomUUID();
    UUID branchPointVersionId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, parentBranchId, 1);
    Recipe otherRecipe = recipeAtVersion(UUID.randomUUID(), UUID.randomUUID(), 1);
    RecipeVersion foreignBp = versionRow(branchPointVersionId, otherRecipe, 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findByRecipeIdAndName(recipeId, "adapter-low-sodium"))
        .thenReturn(Optional.empty());
    when(versionRepository.findById(branchPointVersionId)).thenReturn(Optional.of(foreignBp));

    SaveAdaptedBranchCommand cmd =
        RecipeTestData.defaultSaveAdaptedBranchCommand(
            recipeId, parentBranchId, branchPointVersionId, UUID.randomUUID());

    // The branch-point ownership filter must reject a version owned by another recipe (kills the
    // L1481 "replaced boolean return with true" mutant).
    assertThatThrownBy(() -> service().saveAdaptedBranch(cmd))
        .isInstanceOf(RecipeBranchPointInvalidException.class);
  }

  // ---------------- helpers ----------------

  private static Recipe recipeAtVersion(UUID recipeId, UUID currentBranchId, int currentVersion) {
    return Recipe.builder()
        .id(recipeId)
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .name("Test recipe")
        .description("desc")
        .currentVersion(currentVersion)
        .currentBranchId(currentBranchId)
        .dataQuality(DataQuality.USER_VERIFIED)
        .nutritionStatus(NutritionStatus.PENDING)
        .build();
  }

  private static RecipeBranch branchAtVersion(UUID branchId, Recipe recipe, int currentVersion) {
    return RecipeBranch.builder()
        .id(branchId)
        .recipe(recipe)
        .name("main")
        .currentVersion(currentVersion)
        .divergenceScore(new BigDecimal("0.000"))
        .createdByActor("user:" + UUID.randomUUID())
        .build();
  }

  private static RecipeVersion versionRow(UUID versionId, Recipe recipe, int versionNumber) {
    return RecipeVersion.builder()
        .id(versionId)
        .recipe(recipe)
        .branch(
            RecipeBranch.builder()
                .id(UUID.randomUUID())
                .recipe(recipe)
                .name("main")
                .currentVersion(versionNumber)
                .divergenceScore(new BigDecimal("0.000"))
                .createdByActor("user:" + UUID.randomUUID())
                .build())
        .versionNumber(versionNumber)
        .changeDiff(JsonNodeFactory.instance.objectNode())
        .trigger(com.example.mealprep.recipe.domain.entity.VersionTrigger.MANUAL_CREATE)
        .embeddingStatus("pending")
        .createdByActor("user:" + UUID.randomUUID())
        .ingredients(new ArrayList<>())
        .methodSteps(new ArrayList<>())
        .build();
  }
}
