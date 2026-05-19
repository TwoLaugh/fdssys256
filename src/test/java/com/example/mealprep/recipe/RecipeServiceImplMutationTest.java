package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
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
import com.example.mealprep.recipe.domain.entity.RecipeSubstitution;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
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
import com.example.mealprep.recipe.event.RecipeEvolvedEvent;
import com.example.mealprep.recipe.exception.RecipeSubstitutionNotFoundException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import com.example.mealprep.recipe.exception.SubstitutionRecordPreconditionException;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
 * Mutation-killing unit tests for {@link RecipeServiceImpl} SPI methods (recordSubstitution,
 * storeEmbedding, updateNutritionStatus, updateCharacterFingerprint, getEmbedding, getById) and the
 * private body-building helpers (buildMetadataFromRequest, buildTagsFromRequest,
 * populateIngredientsFromRequests, formatPgVector) that the integration tests exercised but no unit
 * test pinned. Repositories and the event publisher are mocked at the module boundary; the real
 * mappers/differ/applier are used because they are deterministic and central to behaviour.
 */
@ExtendWith(MockitoExtension.class)
class RecipeServiceImplMutationTest {

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
  // Anchored to a recent instant rather than a hardcoded calendar date.
  private final Clock fixedClock = Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC);

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

  // ---------------- build helpers via createRecipe ----------------

  @Test
  void createRecipe_buildsMetadataAndTags_copyingListsAndNormalisingPackable() {
    UUID userId = UUID.randomUUID();
    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    RecipeDto dto = service().createRecipe(userId, RecipeTestData.defaultCreateRequest());

    // buildMetadataFromRequest: non-null lists are copied through (kills L890 null-return and the
    // L898/L906 "!= null ? copy : empty" negate mutants — empty list would drop these values).
    assertThat(dto.currentVersionBody().metadata()).isNotNull();
    assertThat(dto.currentVersionBody().metadata().equipmentRequired())
        .containsExactly("large pan", "colander");
    assertThat(dto.currentVersionBody().metadata().mealTypes()).containsExactly("DINNER");
    // packable() == TRUE in the default request → must survive the
    // `request.packable() != null && request.packable()` (kills L903 negate mutants).
    assertThat(dto.currentVersionBody().metadata().packable()).isTrue();
    assertThat(dto.currentVersionBody().metadata().servings()).isEqualTo(4);

    // buildTagsFromRequest: non-null path, lists copied (kills L923 null-return + L930/L934
    // negate).
    assertThat(dto.currentVersionBody().tags()).isNotNull();
    assertThat(dto.currentVersionBody().tags().protein()).isEqualTo("beef");
    assertThat(dto.currentVersionBody().tags().flavourProfile())
        .containsExactly("savoury", "umami");
  }

  @Test
  void createRecipe_packableFalse_isStoredAsFalse_notDroppedOrInverted() {
    UUID userId = UUID.randomUUID();
    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateRecipeMetadataRequest meta =
        new CreateRecipeMetadataRequest(
            4, 15, 30, 45, List.of("large pan"), 3, 2, false, "Italian", List.of("DINNER"));
    CreateRecipeRequest req =
        new CreateRecipeRequest(
            "Pasta",
            "desc",
            RecipeTestData.defaultIngredients(),
            RecipeTestData.defaultMethod(),
            meta,
            RecipeTestData.defaultTags());

    RecipeDto dto = service().createRecipe(userId, req);

    assertThat(dto.currentVersionBody().metadata().packable()).isFalse();
  }

  @Test
  void createRecipe_nullMetadataLists_yieldEmptyListsNotNull() {
    UUID userId = UUID.randomUUID();
    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateRecipeMetadataRequest meta =
        new CreateRecipeMetadataRequest(2, 5, 10, 15, null, null, null, null, null, null);
    CreateRecipeTagsRequest tags =
        new CreateRecipeTagsRequest("beef", "stovetop", null, null, null);
    CreateRecipeRequest req =
        new CreateRecipeRequest(
            "Pasta",
            "desc",
            RecipeTestData.defaultIngredients(),
            RecipeTestData.defaultMethod(),
            meta,
            tags);

    RecipeDto dto = service().createRecipe(userId, req);

    // Null lists/booleans must map to empty/false, not null — kills the L898/L906/L930/L934
    // negate mutants where `!= null ? copy : empty` would produce the wrong branch.
    assertThat(dto.currentVersionBody().metadata().equipmentRequired()).isEmpty();
    assertThat(dto.currentVersionBody().metadata().mealTypes()).isEmpty();
    assertThat(dto.currentVersionBody().metadata().packable()).isFalse();
    assertThat(dto.currentVersionBody().tags().flavourProfile()).isEmpty();
    assertThat(dto.currentVersionBody().tags().dietaryFlags()).isEmpty();
  }

  @Test
  void createRecipe_ingredientOptionalFlag_isNormalisedFromNullableBoolean() {
    UUID userId = UUID.randomUUID();
    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    List<CreateIngredientRequest> ings =
        List.of(
            new CreateIngredientRequest(
                0, "salt.fine", "Salt", new java.math.BigDecimal("5.000"), "g", null, true),
            new CreateIngredientRequest(
                1, "pepper.black", "Pepper", new java.math.BigDecimal("2.000"), "g", null, null));
    List<CreateMethodStepRequest> method =
        List.of(new CreateMethodStepRequest(1, "Mix everything together.", 2));
    CreateRecipeRequest req =
        new CreateRecipeRequest(
            "Seasoning",
            "desc",
            ings,
            method,
            RecipeTestData.defaultMetadata(),
            RecipeTestData.defaultTags());

    RecipeDto dto = service().createRecipe(userId, req);

    // `optional() != null && optional()` — TRUE stays true, null becomes false (kills the
    // populateIngredientsFromRequests L862 negate mutants).
    assertThat(dto.currentVersionBody().ingredients().get(0).optional()).isTrue();
    assertThat(dto.currentVersionBody().ingredients().get(1).optional()).isFalse();
  }

  // ---------------- getById ----------------

  @Test
  void getById_present_returnsMappedDto_notEmpty() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = userRecipe(UUID.randomUUID());
    UUID branchId = recipe.getCurrentBranchId();
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findAllByRecipeId(recipe.getId())).thenReturn(new ArrayList<>());
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(
            recipe.getId(), branchId, recipe.getCurrentVersion()))
        .thenReturn(Optional.empty());

    Optional<RecipeDto> result = service().getById(recipeId);

    // Kills "replaced return value with Optional.empty" (L215): a present recipe must map through.
    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(recipe.getId());
    assertThat(result.get().name()).isEqualTo("R");
  }

  // ---------------- getEmbedding ----------------

  @Test
  void getEmbedding_nonEmptyArray_present_butEmptyArray_filteredOut() {
    UUID versionId = UUID.randomUUID();
    RecipeVersion withEmbedding = bareVersion(versionId);
    withEmbedding.setEmbedding(new float[] {0.1f, 0.2f});
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(withEmbedding));

    Optional<float[]> present = service().getEmbedding(versionId);
    assertThat(present).isPresent();
    assertThat(present.get()).containsExactly(0.1f, 0.2f);

    UUID emptyId = UUID.randomUUID();
    RecipeVersion emptyArr = bareVersion(emptyId);
    emptyArr.setEmbedding(new float[0]);
    when(versionRepository.findById(emptyId)).thenReturn(Optional.of(emptyArr));

    // arr.length > 0 is the boundary: a length-0 array must be filtered out (kills the
    // lambda$getEmbedding$40 boundary + boolean-true mutants).
    assertThat(service().getEmbedding(emptyId)).isEmpty();
  }

  // ---------------- updateNutritionStatus ----------------

  @Test
  void updateNutritionStatus_publishesEventWithRecipeId_andPersistsBoth() {
    UUID versionId = UUID.randomUUID();
    Recipe recipe = userRecipe(UUID.randomUUID());
    RecipeVersion version = bareVersion(versionId);
    version.setRecipe(recipe);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    service()
        .updateNutritionStatus(
            versionId,
            NutritionStatus.CALCULATED,
            JsonNodeFactory.instance.objectNode().put("kcal", 500));

    assertThat(recipe.getNutritionStatus()).isEqualTo(NutritionStatus.CALCULATED);
    ArgumentCaptor<Object> evCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(evCaptor.capture());
    RecipeEvolvedEvent ev = (RecipeEvolvedEvent) evCaptor.getValue();
    // `recipe != null ? recipe.getId() : null` — the recipe IS present so the event must carry
    // its id (kills the L1688 negate mutant which would publish a null recipeId).
    assertThat(ev.recipeId()).isEqualTo(recipe.getId());
    assertThat(ev.versionId()).isEqualTo(versionId);
  }

  @Test
  void updateNutritionStatus_missingVersion_throws_andPublishesNoEvent() {
    UUID versionId = UUID.randomUUID();
    when(versionRepository.findById(versionId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .updateNutritionStatus(
                        versionId,
                        NutritionStatus.CALCULATED,
                        JsonNodeFactory.instance.objectNode()))
        .isInstanceOf(RecipeVersionNotFoundException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------------- updateCharacterFingerprint ----------------

  @Test
  void updateCharacterFingerprint_publishesEventWithRecipeId() {
    UUID versionId = UUID.randomUUID();
    Recipe recipe = userRecipe(UUID.randomUUID());
    RecipeVersion version = bareVersion(versionId);
    version.setRecipe(recipe);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().updateCharacterFingerprint(versionId, RecipeTestData.defaultFingerprint());

    ArgumentCaptor<Object> evCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(evCaptor.capture());
    RecipeEvolvedEvent ev = (RecipeEvolvedEvent) evCaptor.getValue();
    // `version.getRecipe() != null ? getId() : null` — recipe present → event recipeId is set
    // (kills the L1713 negate mutant).
    assertThat(ev.recipeId()).isEqualTo(recipe.getId());
    assertThat(ev.versionId()).isEqualTo(versionId);
  }

  // ---------------- storeEmbedding (also covers formatPgVector) ----------------

  @Test
  void storeEmbedding_formatsPgVectorLiteral_andPublishesEvent() {
    UUID versionId = UUID.randomUUID();
    Recipe recipe = userRecipe(UUID.randomUUID());
    RecipeVersion version = bareVersion(versionId);
    version.setRecipe(recipe);
    when(versionRepository.updateEmbedding(
            eq(versionId), any(String.class), eq("model-x"), any(Instant.class)))
        .thenReturn(1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    service().storeEmbedding(versionId, new float[] {1.0f, 2.5f, -3.0f}, "model-x");

    ArgumentCaptor<String> pgCaptor = ArgumentCaptor.forClass(String.class);
    verify(versionRepository)
        .updateEmbedding(eq(versionId), pgCaptor.capture(), eq("model-x"), any(Instant.class));
    // formatPgVector: '[v1,v2,...]' with comma separators between (and only between) elements.
    // Kills the L1795 +/* arithmetic, L1797/L1798 conditional + boundary, L1804 empty-return
    // mutants — a wrong buffer/sep/return would not produce this exact literal.
    assertThat(pgCaptor.getValue()).isEqualTo("[1.0,2.5,-3.0]");

    ArgumentCaptor<Object> evCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(evCaptor.capture());
    assertThat(((RecipeEvolvedEvent) evCaptor.getValue()).recipeId()).isEqualTo(recipe.getId());
  }

  @Test
  void storeEmbedding_singleElement_hasNoLeadingComma() {
    UUID versionId = UUID.randomUUID();
    RecipeVersion version = bareVersion(versionId);
    version.setRecipe(userRecipe(UUID.randomUUID()));
    when(versionRepository.updateEmbedding(
            eq(versionId), any(String.class), any(String.class), any(Instant.class)))
        .thenReturn(1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    service().storeEmbedding(versionId, new float[] {7.0f}, "m");

    ArgumentCaptor<String> pgCaptor = ArgumentCaptor.forClass(String.class);
    verify(versionRepository)
        .updateEmbedding(eq(versionId), pgCaptor.capture(), any(String.class), any(Instant.class));
    // `if (i > 0) append(',')` — index 0 must NOT emit a comma (kills the L1797/L1798 negate +
    // boundary mutants which would yield "[,7.0]" or drop the only element).
    assertThat(pgCaptor.getValue()).isEqualTo("[7.0]");
  }

  @Test
  void storeEmbedding_noRowsUpdated_throws() {
    UUID versionId = UUID.randomUUID();
    when(versionRepository.updateEmbedding(
            eq(versionId), any(String.class), any(String.class), any(Instant.class)))
        .thenReturn(0);

    assertThatThrownBy(() -> service().storeEmbedding(versionId, new float[] {1.0f}, "m"))
        .isInstanceOf(RecipeVersionNotFoundException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------------- recordSubstitution ----------------

  @Test
  void recordSubstitution_appendsPlanId_preservingExistingViaArraycopy() {
    UUID substitutionId = UUID.randomUUID();
    UUID existingPlan = UUID.randomUUID();
    UUID newPlan = UUID.randomUUID();
    RecipeSubstitution sub = acceptedSub(substitutionId);
    sub.setAppliedInPlanIds(new UUID[] {existingPlan});
    sub.setApplicationCount(1);
    when(substitutionRepository.findById(substitutionId)).thenReturn(Optional.of(sub));
    when(substitutionRepository.save(any(RecipeSubstitution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().recordSubstitution(substitutionId, newPlan);

    ArgumentCaptor<RecipeSubstitution> captor = ArgumentCaptor.forClass(RecipeSubstitution.class);
    verify(substitutionRepository).save(captor.capture());
    // System.arraycopy must carry the existing plan id forward (kills the L1839 "removed call to
    // arraycopy" mutant which would lose `existingPlan` and leave a [null, newPlan] array).
    assertThat(captor.getValue().getAppliedInPlanIds()).containsExactly(existingPlan, newPlan);
    assertThat(captor.getValue().getApplicationCount()).isEqualTo(2);
  }

  @Test
  void recordSubstitution_alreadyRecordedPlan_isIdempotent_noSave() {
    UUID substitutionId = UUID.randomUUID();
    UUID plan = UUID.randomUUID();
    RecipeSubstitution sub = acceptedSub(substitutionId);
    sub.setAppliedInPlanIds(new UUID[] {plan});
    sub.setApplicationCount(1);
    when(substitutionRepository.findById(substitutionId)).thenReturn(Optional.of(sub));

    service().recordSubstitution(substitutionId, plan);

    verify(substitutionRepository, never()).save(any());
    assertThat(sub.getApplicationCount()).isEqualTo(1);
  }

  @Test
  void recordSubstitution_notAccepted_throwsPrecondition() {
    UUID substitutionId = UUID.randomUUID();
    RecipeSubstitution sub = acceptedSub(substitutionId);
    sub.setState(com.example.mealprep.recipe.api.dto.SubstitutionState.PROPOSED);
    when(substitutionRepository.findById(substitutionId)).thenReturn(Optional.of(sub));

    assertThatThrownBy(() -> service().recordSubstitution(substitutionId, UUID.randomUUID()))
        .isInstanceOf(SubstitutionRecordPreconditionException.class);
  }

  @Test
  void recordSubstitution_missing_throwsNotFound() {
    UUID substitutionId = UUID.randomUUID();
    when(substitutionRepository.findById(substitutionId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().recordSubstitution(substitutionId, UUID.randomUUID()))
        .isInstanceOf(RecipeSubstitutionNotFoundException.class);
  }

  // ---------------- helpers ----------------

  private static Recipe userRecipe(UUID userId) {
    return Recipe.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .catalogue(Catalogue.USER)
        .name("R")
        .currentVersion(1)
        .currentBranchId(UUID.randomUUID())
        .dataQuality(DataQuality.USER_VERIFIED)
        .nutritionStatus(NutritionStatus.PENDING)
        .build();
  }

  private static RecipeVersion bareVersion(UUID id) {
    return RecipeVersion.builder()
        .id(id)
        .versionNumber(1)
        .changeDiff(JsonNodeFactory.instance.objectNode())
        .trigger(VersionTrigger.MANUAL_CREATE)
        .embeddingStatus("pending")
        .createdByActor("user:" + UUID.randomUUID())
        .ingredients(new ArrayList<>())
        .methodSteps(new ArrayList<>())
        .build();
  }

  private static RecipeSubstitution acceptedSub(UUID id) {
    return RecipeSubstitution.builder()
        .id(id)
        .recipeId(UUID.randomUUID())
        .versionId(UUID.randomUUID())
        .branchId(UUID.randomUUID())
        .originalMappingKey("beef.mince")
        .originalQuantity(new java.math.BigDecimal("500.000"))
        .originalUnit("g")
        .substituteMappingKey("soy.crumble")
        .substituteQuantity(new java.math.BigDecimal("400.000"))
        .substituteUnit("g")
        .reason(com.example.mealprep.recipe.api.dto.SubstitutionReason.DIETARY_TEMP)
        .temporary(true)
        .applicationCount(0)
        .state(com.example.mealprep.recipe.api.dto.SubstitutionState.ACCEPTED)
        .createdAt(Instant.now().minusSeconds(120))
        .createdByActor("user:test")
        .build();
  }
}
