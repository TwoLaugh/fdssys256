package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
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
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
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
import com.example.mealprep.recipe.event.AdaptationOutcomeType;
import com.example.mealprep.recipe.event.RecipeAdaptedEvent;
import com.example.mealprep.recipe.event.RecipeEvolvedEvent;
import com.example.mealprep.recipe.event.RecipeUpdatedEvent;
import com.example.mealprep.recipe.exception.RecipeBranchNameConflictException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.exception.RecipeVersionConflictException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import com.example.mealprep.recipe.spi.SaveAdaptedBranchCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit-level coverage for the {@code RecipeWriteApi} SPI methods on {@link RecipeServiceImpl}.
 * Race-checks, append-only writes, and event emission are exercised at the mock boundary; the
 * end-to-end DB path is covered in {@code RecipeWriteApiIT}.
 */
@ExtendWith(MockitoExtension.class)
class RecipeWriteApiTest {

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
  private final ParsedRecipeToCreateRequestMapper parserToCreateRequestMapper =
      new ParsedRecipeToCreateRequestMapper();
  private final VersionDiffer versionDiffer = new VersionDiffer(objectMapper);
  private final DivergenceScoreCalculator divergenceCalculator = new DivergenceScoreCalculator();
  private final FingerprintDeriver fingerprintDeriver = new FingerprintDeriver();
  private final SubstitutionOverlayApplier overlayApplier = new SubstitutionOverlayApplier();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC);

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
        parserToCreateRequestMapper,
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
  void saveAdaptedVersion_throwsConflict_whenParentVersionNumberMismatches() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID parentVersionId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, branchId, 3);
    when(recipeRepository.findByIdForUpdate(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId))
        .thenReturn(Optional.of(branchAtVersion(branchId, recipe, 3)));

    SaveAdaptedVersionCommand cmd =
        RecipeTestData.defaultSaveAdaptedVersionCommand(
            recipeId, branchId, 2, parentVersionId, UUID.randomUUID());

    assertThatThrownBy(() -> service().saveAdaptedVersion(cmd))
        .isInstanceOf(RecipeVersionConflictException.class);
  }

  @Test
  void saveAdaptedVersion_throwsConflict_whenParentVersionIdMismatches() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID expectedParentVersionId = UUID.randomUUID();
    UUID actualHeadId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, branchId, 3);
    RecipeBranch branch = branchAtVersion(branchId, recipe, 3);
    when(recipeRepository.findByIdForUpdate(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findCurrentVersionId(recipeId, branchId, 3))
        .thenReturn(Optional.of(actualHeadId));

    SaveAdaptedVersionCommand cmd =
        RecipeTestData.defaultSaveAdaptedVersionCommand(
            recipeId, branchId, 3, expectedParentVersionId, UUID.randomUUID());

    assertThatThrownBy(() -> service().saveAdaptedVersion(cmd))
        .isInstanceOf(RecipeVersionConflictException.class);
  }

  @Test
  void saveAdaptedVersion_happy_bumpsHead_andPublishesEvents() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID parentVersionId = UUID.randomUUID();
    UUID adapterTraceId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, branchId, 3);
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

    SaveAdaptedVersionCommand cmd =
        RecipeTestData.defaultSaveAdaptedVersionCommand(
            recipeId, branchId, 3, parentVersionId, adapterTraceId);

    var dto = service().saveAdaptedVersion(cmd);

    assertThat(dto.versionNumber()).isEqualTo(4);
    assertThat(dto.adapterTraceId()).isEqualTo(adapterTraceId);
    assertThat(recipe.getCurrentVersion()).isEqualTo(4);
    assertThat(recipe.getNutritionStatus()).isEqualTo(NutritionStatus.PENDING);

    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    RecipeVersion savedVersion = versionCaptor.getValue();
    assertThat(savedVersion.getCreatedByActor()).isEqualTo("pipeline:" + adapterTraceId);
    // "pipeline:" (9) + UUID toString (36) = 45 chars, fits varchar(64).
    assertThat(savedVersion.getCreatedByActor().length()).isEqualTo(45);
    assertThat(savedVersion.getCreatedByActor().length()).isLessThanOrEqualTo(64);
    assertThat(savedVersion.getAdapterTraceId()).isEqualTo(adapterTraceId);

    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(3)).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues())
        .anyMatch(RecipeUpdatedEvent.class::isInstance)
        .anyMatch(
            e ->
                e instanceof RecipeAdaptedEvent ev
                    && ev.outcomeType() == AdaptationOutcomeType.NEW_VERSION
                    && adapterTraceId.equals(ev.adapterTraceId()));
  }

  @Test
  void saveAdaptedVersion_recipeMissing_throws404() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    when(recipeRepository.findByIdForUpdate(recipeId)).thenReturn(Optional.empty());

    SaveAdaptedVersionCommand cmd =
        RecipeTestData.defaultSaveAdaptedVersionCommand(
            recipeId, branchId, 1, UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(() -> service().saveAdaptedVersion(cmd))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  // ---------------- saveAdaptedBranch ----------------

  @Test
  void saveAdaptedBranch_duplicateName_throws409() {
    UUID recipeId = UUID.randomUUID();
    UUID parentBranchId = UUID.randomUUID();
    UUID branchPointVersionId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, parentBranchId, 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findByRecipeIdAndName(recipeId, "adapter-low-sodium"))
        .thenReturn(Optional.of(branchAtVersion(UUID.randomUUID(), recipe, 1)));

    SaveAdaptedBranchCommand cmd =
        RecipeTestData.defaultSaveAdaptedBranchCommand(
            recipeId, parentBranchId, branchPointVersionId, UUID.randomUUID());

    assertThatThrownBy(() -> service().saveAdaptedBranch(cmd))
        .isInstanceOf(RecipeBranchNameConflictException.class);
  }

  @Test
  void saveAdaptedBranch_doesNotBumpRecipeHead() {
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
    assertThat(recipe.getCurrentVersion()).isEqualTo(5);

    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(3)).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues())
        .anyMatch(
            e ->
                e instanceof RecipeAdaptedEvent ev
                    && ev.outcomeType() == AdaptationOutcomeType.BRANCH);
  }

  // ---------------- updateNutritionStatus / FP / divergence / embedding ----------------

  @Test
  void updateNutritionStatus_writesJson_andPublishesEvolvedEvent() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Recipe recipe = recipeAtVersion(recipeId, branchId, 1);
    RecipeVersion version = versionRow(versionId, recipe, 1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    com.fasterxml.jackson.databind.JsonNode payload =
        JsonNodeFactory.instance.objectNode().put("caloriesPerServing", 520);
    service().updateNutritionStatus(versionId, NutritionStatus.CALCULATED, payload);

    assertThat(version.getNutritionPerServing()).isEqualTo(payload);
    assertThat(recipe.getNutritionStatus()).isEqualTo(NutritionStatus.CALCULATED);

    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue())
        .isInstanceOfSatisfying(
            RecipeEvolvedEvent.class,
            ev -> {
              assertThat(ev.reason())
                  .isEqualTo(RecipeEvolvedEvent.EvolvedReason.NUTRITION_RECALCULATED);
              assertThat(ev.versionId()).isEqualTo(versionId);
            });
  }

  @Test
  void updateNutritionStatus_missingVersion_throws404() {
    UUID versionId = UUID.randomUUID();
    when(versionRepository.findById(versionId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .updateNutritionStatus(
                        versionId, NutritionStatus.CALCULATED, JsonNodeFactory.instance.nullNode()))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void updateCharacterFingerprint_writesJson_andPublishesEvolvedEvent() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = recipeAtVersion(recipeId, UUID.randomUUID(), 1);
    RecipeVersion version = versionRow(versionId, recipe, 1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CharacterFingerprintDto fp =
        new CharacterFingerprintDto(
            List.of("flour"),
            List.of("baking"),
            List.of("crisp"),
            List.of("sweet"),
            Complexity.MINIMAL,
            "French");
    service().updateCharacterFingerprint(versionId, fp);

    assertThat(version.getCharacterFingerprint()).isNotNull();

    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue())
        .isInstanceOfSatisfying(
            RecipeEvolvedEvent.class,
            ev ->
                assertThat(ev.reason())
                    .isEqualTo(RecipeEvolvedEvent.EvolvedReason.FINGERPRINT_REFRESHED));
  }

  @Test
  void updateBranchDivergence_writesScore_noEvent() {
    UUID branchId = UUID.randomUUID();
    RecipeBranch branch =
        branchAtVersion(branchId, recipeAtVersion(UUID.randomUUID(), branchId, 1), 1);
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(branchRepository.saveAndFlush(any(RecipeBranch.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().updateBranchDivergence(branchId, new BigDecimal("0.420"));

    assertThat(branch.getDivergenceScore()).isEqualByComparingTo("0.420");
    verify(eventPublisher, times(0)).publishEvent(any());
  }

  @Test
  void storeEmbedding_flipsStatusAndPublishesEvent() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = recipeAtVersion(recipeId, UUID.randomUUID(), 1);
    RecipeVersion version = versionRow(versionId, recipe, 1);
    // New impl: native UPDATE via versionRepository.updateEmbedding(...) returns affected row
    // count; non-zero means the row was found. The findById is now only used to fetch the
    // recipeId for the published event (the row itself is mutated server-side by the UPDATE).
    when(versionRepository.updateEmbedding(
            org.mockito.ArgumentMatchers.eq(versionId),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("openai:text-embedding-3-small"),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    float[] vector = {0.1f, 0.2f};
    service().storeEmbedding(versionId, vector, "openai:text-embedding-3-small");

    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue())
        .isInstanceOfSatisfying(
            RecipeEvolvedEvent.class,
            ev -> {
              assertThat(ev.reason()).isEqualTo(RecipeEvolvedEvent.EvolvedReason.EMBEDDING_STORED);
              assertThat(ev.recipeId()).isEqualTo(recipeId);
              assertThat(ev.versionId()).isEqualTo(versionId);
            });
  }

  @Test
  void markEmbeddingFailed_flipsStatus_doesNotClearEmbedding_andPublishesEvent() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = recipeAtVersion(recipeId, UUID.randomUUID(), 1);
    RecipeVersion version = versionRow(versionId, recipe, 1);
    when(versionRepository.markEmbeddingFailed(versionId)).thenReturn(1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    service().markEmbeddingFailed(versionId);

    // Native UPDATE only touches embedding_status — it does NOT clear the embedding vector
    // (see SQL in RecipeVersionRepository#markEmbeddingFailed).
    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue())
        .isInstanceOfSatisfying(
            RecipeEvolvedEvent.class,
            ev -> {
              assertThat(ev.reason()).isEqualTo(RecipeEvolvedEvent.EvolvedReason.EMBEDDING_FAILED);
              assertThat(ev.recipeId()).isEqualTo(recipeId);
            });
  }

  @Test
  void markEmbeddingFailed_throws404OnMissingVersion() {
    UUID versionId = UUID.randomUUID();
    // Native UPDATE returns 0 affected rows when the version does not exist.
    when(versionRepository.markEmbeddingFailed(versionId)).thenReturn(0);
    assertThatThrownBy(() -> service().markEmbeddingFailed(versionId))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void getEmbedding_returnsVectorWhenPopulated() {
    UUID versionId = UUID.randomUUID();
    Recipe recipe = recipeAtVersion(UUID.randomUUID(), UUID.randomUUID(), 1);
    RecipeVersion version = versionRow(versionId, recipe, 1);
    version.setEmbedding(new float[] {0.1f, 0.2f, 0.3f});
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    Optional<float[]> result = service().getEmbedding(versionId);

    assertThat(result).isPresent();
    assertThat(result.get()).containsExactly(0.1f, 0.2f, 0.3f);
  }

  @Test
  void getEmbedding_emptyWhenVectorNull() {
    UUID versionId = UUID.randomUUID();
    Recipe recipe = recipeAtVersion(UUID.randomUUID(), UUID.randomUUID(), 1);
    RecipeVersion version = versionRow(versionId, recipe, 1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    assertThat(service().getEmbedding(versionId)).isEmpty();
  }

  @Test
  void getEmbedding_emptyWhenVersionMissing() {
    UUID versionId = UUID.randomUUID();
    when(versionRepository.findById(versionId)).thenReturn(Optional.empty());
    assertThat(service().getEmbedding(versionId)).isEmpty();
  }

  // ---------------- helpers ----------------

  private static Recipe recipeAtVersion(UUID recipeId, UUID currentBranchId, int currentVersion) {
    return Recipe.builder()
        .id(recipeId)
        .userId(UUID.randomUUID())
        .catalogue(com.example.mealprep.recipe.domain.entity.Catalogue.USER)
        .name("Test recipe")
        .description("desc")
        .currentVersion(currentVersion)
        .currentBranchId(currentBranchId)
        .dataQuality(com.example.mealprep.recipe.domain.entity.DataQuality.USER_VERIFIED)
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
        .ingredients(new ArrayList<RecipeIngredient>())
        .methodSteps(new ArrayList<>())
        .build();
  }
}
