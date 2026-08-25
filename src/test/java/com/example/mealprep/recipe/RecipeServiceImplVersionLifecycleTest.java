package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.ConfirmImportRequest;
import com.example.mealprep.recipe.api.dto.ImportJobArchiveResult;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromHtmlRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.MethodOverlayLineRequest;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportPreview;
import com.example.mealprep.recipe.api.dto.RecipeSearchCriteriaDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.SubstitutionItemRequest;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
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
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.ImportSource;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeImport;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeMethodStep;
import com.example.mealprep.recipe.domain.entity.RecipeSubstitution;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.repository.RecipeBranchRepository;
import com.example.mealprep.recipe.domain.repository.RecipeImportRepository;
import com.example.mealprep.recipe.domain.repository.RecipeIngredientRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRatingRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeSubstitutionRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.internal.DivergenceScoreCalculator;
import com.example.mealprep.recipe.domain.service.internal.FingerprintDeriver;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser;
import com.example.mealprep.recipe.domain.service.internal.RecipeDeduplicationService;
import com.example.mealprep.recipe.domain.service.internal.RecipeServiceImpl;
import com.example.mealprep.recipe.domain.service.internal.SubstitutionOverlayApplier;
import com.example.mealprep.recipe.domain.service.internal.VersionDiffer;
import com.example.mealprep.recipe.event.AdaptationOutcomeType;
import com.example.mealprep.recipe.event.ArchiveCause;
import com.example.mealprep.recipe.event.RecipeAdaptedEvent;
import com.example.mealprep.recipe.event.RecipeArchivedEvent;
import com.example.mealprep.recipe.event.RecipeBranchCreatedEvent;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.example.mealprep.recipe.event.RecipePromotedEvent;
import com.example.mealprep.recipe.event.RecipeSubstitutionCreatedEvent;
import com.example.mealprep.recipe.event.RecipeSubstitutionStateChangedEvent;
import com.example.mealprep.recipe.event.RecipeUpdatedEvent;
import com.example.mealprep.recipe.event.RecipeVersionCreatedEvent;
import com.example.mealprep.recipe.exception.NoChangesException;
import com.example.mealprep.recipe.exception.RecipeBranchNameConflictException;
import com.example.mealprep.recipe.exception.RecipeBranchNameReservedException;
import com.example.mealprep.recipe.exception.RecipeBranchNotFoundException;
import com.example.mealprep.recipe.exception.RecipeBranchPointInvalidException;
import com.example.mealprep.recipe.exception.RecipeCatalogueViolationException;
import com.example.mealprep.recipe.exception.RecipeDiffCrossBranchException;
import com.example.mealprep.recipe.exception.RecipeDiffNotComputedException;
import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.exception.RecipeSubstitutionNotFoundException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import com.example.mealprep.recipe.exception.SubstitutionOriginalNotInVersionException;
import com.example.mealprep.recipe.extraction.ExtractionLayer;
import com.example.mealprep.recipe.extraction.ExtractionProvenance;
import com.example.mealprep.recipe.extraction.ParsedRecipe;
import com.example.mealprep.recipe.extraction.RecipeExtractionService;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import com.example.mealprep.recipe.spi.ImportedRecipeResult;
import com.example.mealprep.recipe.spi.SaveAdaptedSubstitutionCommand;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link RecipeServiceImpl}'s version-lifecycle flows (manual edit, branch creation,
 * revert, substitution promotion), the discovery-import persistence path, the preview/confirm
 * import surface, catalogue promotion/demotion and archive operations. Repositories and the event
 * publisher are mocked at the module boundary; the real mappers, differ, deriver and overlay
 * applier are used because they are deterministic and central to behaviour.
 */
@ExtendWith(MockitoExtension.class)
class RecipeServiceImplVersionLifecycleTest {

  @Mock private RecipeRepository recipeRepository;
  @Mock private RecipeBranchRepository branchRepository;
  @Mock private RecipeVersionRepository versionRepository;
  @Mock private RecipeImportRepository importRepository;
  @Mock private RecipeIngredientRepository ingredientRepository;
  @Mock private RecipeSubstitutionRepository substitutionRepository;
  @Mock private RecipeRatingRepository ratingRepository;
  @Mock private UrlFetcher urlFetcher;
  @Mock private RecipeExtractionService extractionService;
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
  private final Instant fixedInstant = Instant.now().minusSeconds(7200);
  private final Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

  @AfterEach
  void clearMdc() {
    MDC.remove("traceId");
  }

  private RecipeServiceImpl service() {
    return new RecipeServiceImpl(
        recipeRepository,
        branchRepository,
        versionRepository,
        importRepository,
        ingredientRepository,
        substitutionRepository,
        ratingRepository,
        recipeMapper,
        versionMapper,
        branchMapper,
        importMapper,
        diffMapper,
        substitutionMapper,
        urlFetcher,
        htmlImportParser,
        new ParsedRecipeToCreateRequestMapper(),
        extractionService,
        new RecipeDeduplicationService(recipeRepository),
        versionDiffer,
        divergenceCalculator,
        fingerprintDeriver,
        overlayApplier,
        objectMapper,
        eventPublisher,
        fixedClock);
  }

  // ---------------- entity fixtures ----------------

  private static Recipe ownedRecipe(UUID recipeId, UUID userId, UUID branchId) {
    return Recipe.builder()
        .id(recipeId)
        .userId(userId)
        .catalogue(Catalogue.USER)
        .name("Old Name")
        .description("Old description")
        .currentVersion(1)
        .currentBranchId(branchId)
        .dataQuality(DataQuality.USER_VERIFIED)
        .nutritionStatus(NutritionStatus.PENDING)
        .build();
  }

  private static Recipe systemRecipe(UUID recipeId) {
    return Recipe.builder()
        .id(recipeId)
        .userId(new UUID(0L, 0L))
        .catalogue(Catalogue.SYSTEM)
        .name("Discovered Dish")
        .currentVersion(1)
        .currentBranchId(null)
        .dataQuality(DataQuality.WEB_DISCOVERED)
        .nutritionStatus(NutritionStatus.PENDING)
        .build();
  }

  private static RecipeBranch branchOf(
      Recipe recipe, UUID branchId, String name, int currentVersion) {
    return RecipeBranch.builder()
        .id(branchId)
        .recipe(recipe)
        .name(name)
        .currentVersion(currentVersion)
        .divergenceScore(new BigDecimal("0.000"))
        .createdByActor("user:test")
        .build();
  }

  private static RecipeIngredient ing(
      RecipeVersion version, int order, String key, String display, String qty) {
    return RecipeIngredient.builder()
        .id(UUID.randomUUID())
        .version(version)
        .lineOrder(order)
        .ingredientMappingKey(key)
        .displayName(display)
        .quantity(new BigDecimal(qty))
        .unit("g")
        .preparation(null)
        .optional(false)
        .needsReview(false)
        .build();
  }

  private static RecipeMethodStep step(
      RecipeVersion version, int number, String instruction, Integer mins) {
    return RecipeMethodStep.builder()
        .id(UUID.randomUUID())
        .version(version)
        .stepNumber(number)
        .instruction(instruction)
        .durationMinutes(mins)
        .build();
  }

  private static RecipeVersion bareVersion(
      UUID id, Recipe recipe, RecipeBranch branch, int number) {
    return RecipeVersion.builder()
        .id(id)
        .recipe(recipe)
        .branch(branch)
        .versionNumber(number)
        .changeDiff(JsonNodeFactory.instance.objectNode())
        .trigger(VersionTrigger.MANUAL_CREATE)
        .embeddingStatus("pending")
        .createdByActor("user:test")
        .ingredients(new ArrayList<>())
        .methodSteps(new ArrayList<>())
        .build();
  }

  /** Body mirrors {@link RecipeTestData#defaultCreateRequest()} exactly. */
  private static RecipeVersion versionWithDefaultBody(
      UUID id, Recipe recipe, RecipeBranch branch, int number) {
    RecipeVersion version = bareVersion(id, recipe, branch, number);
    version.getIngredients().add(ing(version, 0, "spaghetti.dry", "Spaghetti", "400.000"));
    version.getIngredients().add(ing(version, 1, "beef.mince", "Lean beef mince", "500.000"));
    version.getIngredients().add(ing(version, 2, "tomato.passata", "Passata", "700.000"));
    version.getMethodSteps().add(step(version, 1, "Brown the mince in a wide pan.", 8));
    version.getMethodSteps().add(step(version, 2, "Add passata and simmer for 25 minutes.", 25));
    version.getMethodSteps().add(step(version, 3, "Cook spaghetti to al dente; drain.", 9));
    version.setMetadata(
        RecipeMetadata.builder()
            .id(UUID.randomUUID())
            .version(version)
            .servings(4)
            .prepTimeMins(15)
            .cookTimeMins(30)
            .totalTimeMins(45)
            .equipmentRequired(new ArrayList<>(List.of("large pan", "colander")))
            .fridgeDays(3)
            .freezerWeeks(2)
            .packable(true)
            .cuisine("Italian")
            .mealTypes(new ArrayList<>(List.of("DINNER")))
            .build());
    version.setTags(
        RecipeTags.builder()
            .id(UUID.randomUUID())
            .version(version)
            .protein("beef")
            .cookingMethod("stovetop")
            .complexity(Complexity.MODERATE)
            .flavourProfile(new ArrayList<>(List.of("savoury", "umami")))
            .dietaryFlags(new ArrayList<>())
            .build());
    return version;
  }

  /** A distinctly different body, used as the revert target. */
  private static RecipeVersion versionWithTofuBody(
      UUID id, Recipe recipe, RecipeBranch branch, int number) {
    RecipeVersion version = bareVersion(id, recipe, branch, number);
    version.getIngredients().add(ing(version, 0, "spaghetti.dry", "Spaghetti", "400.000"));
    version.getIngredients().add(ing(version, 1, "tofu.firm", "Firm tofu", "300.000"));
    version.getMethodSteps().add(step(version, 1, "Press the tofu.", 5));
    version.getMethodSteps().add(step(version, 2, "Simmer the sauce.", 20));
    version.setMetadata(
        RecipeMetadata.builder()
            .id(UUID.randomUUID())
            .version(version)
            .servings(2)
            .prepTimeMins(10)
            .cookTimeMins(20)
            .totalTimeMins(30)
            .equipmentRequired(new ArrayList<>(List.of("wok")))
            .fridgeDays(1)
            .freezerWeeks(1)
            .packable(true)
            .cuisine("Fusion")
            .mealTypes(new ArrayList<>(List.of("LUNCH")))
            .build());
    version.setTags(
        RecipeTags.builder()
            .id(UUID.randomUUID())
            .version(version)
            .protein("tofu")
            .cookingMethod("wok")
            .complexity(Complexity.MINIMAL)
            .flavourProfile(new ArrayList<>(List.of("savoury")))
            .dietaryFlags(new ArrayList<>(List.of("vegan")))
            .build());
    return version;
  }

  private static RecipeSubstitution beefToSoySub(
      UUID id, UUID recipeId, UUID versionId, UUID branchId, SubstitutionState state) {
    return RecipeSubstitution.builder()
        .id(id)
        .recipeId(recipeId)
        .versionId(versionId)
        .branchId(branchId)
        .originalMappingKey("beef.mince")
        .originalQuantity(new BigDecimal("500.000"))
        .originalUnit("g")
        .substituteMappingKey("soy.crumble")
        .substituteQuantity(new BigDecimal("400.000"))
        .substituteUnit("g")
        .reason(SubstitutionReason.DIETARY_TEMP)
        .temporary(true)
        .appliedInPlanIds(new UUID[0])
        .applicationCount(0)
        .state(state)
        .createdAt(Instant.now().minusSeconds(300))
        .createdByActor("user:test")
        .build();
  }

  private void stubIdentitySaves() {
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.saveAndFlush(any(RecipeBranch.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private List<Object> publishedEvents(int expected) {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(expected)).publishEvent(captor.capture());
    return captor.getAllValues();
  }

  private static <T> T onlyEvent(List<Object> events, Class<T> type) {
    List<T> matches = events.stream().filter(type::isInstance).map(type::cast).toList();
    assertThat(matches).hasSize(1);
    return matches.get(0);
  }

  // ---------------- manualEdit ----------------

  @Test
  void manualEdit_writesNextVersion_movesPointers_andPublishesEvents() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    UUID mdcTrace = UUID.randomUUID();
    MDC.put("traceId", mdcTrace.toString());

    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion parent = versionWithDefaultBody(parentId, recipe, branch, 1);
    List<RecipeMethodStep> stepsSpy = spy(new ArrayList<>(parent.getMethodSteps()));
    parent.setMethodSteps(stepsSpy);
    List<String> equipmentSpy = spy(new ArrayList<>(List.of("large pan", "colander")));
    parent.getMetadata().setEquipmentRequired(equipmentSpy);
    List<String> mealTypesSpy = spy(new ArrayList<>(List.of("DINNER")));
    parent.getMetadata().setMealTypes(mealTypesSpy);
    List<String> flavourSpy = spy(new ArrayList<>(List.of("savoury", "umami")));
    parent.getTags().setFlavourProfile(flavourSpy);
    List<String> dietarySpy = spy(new ArrayList<>());
    parent.getTags().setDietaryFlags(dietarySpy);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findCurrentVersionId(recipeId, branchId, 1))
        .thenReturn(Optional.of(parentId));
    when(versionRepository.findById(parentId)).thenReturn(Optional.of(parent));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    stubIdentitySaves();
    when(branchRepository.findAllByRecipeId(recipeId)).thenReturn(List.of(branch));

    RecipeDto dto =
        service().manualEdit(recipeId, RecipeTestData.defaultManualEditRequest(0), userId);

    assertThat(dto).isNotNull();
    assertThat(dto.name()).isEqualTo("Spaghetti Bolognese");
    assertThat(dto.description()).isEqualTo("Hearty weeknight pasta.");
    assertThat(dto.currentVersion()).isEqualTo(2);
    assertThat(dto.currentVersionBody().versionNumber()).isEqualTo(2);
    assertThat(dto.currentVersionBody().parentVersionId()).isEqualTo(parentId);
    assertThat(dto.currentVersionBody().trigger()).isEqualTo(VersionTrigger.MANUAL_EDIT);
    assertThat(dto.currentVersionBody().changeReason())
        .isEqualTo("Simmer longer for deeper flavour.");

    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    RecipeVersion saved = versionCaptor.getValue();
    assertThat(saved.getVersionNumber()).isEqualTo(2);
    assertThat(saved.getIngredients()).hasSize(3);
    assertThat(saved.getMethodSteps())
        .extracting(RecipeMethodStep::getDurationMinutes)
        .containsExactly(8, 35, 9);
    assertThat(saved.getMetadata()).isNotNull();
    assertThat(saved.getMetadata().getServings()).isEqualTo(4);
    assertThat(saved.getTags()).isNotNull();
    assertThat(saved.getTags().getProtein()).isEqualTo("beef");

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getCurrentVersion()).isEqualTo(2);
    assertThat(recipeCaptor.getValue().getName()).isEqualTo("Spaghetti Bolognese");
    assertThat(recipeCaptor.getValue().getDescription()).isEqualTo("Hearty weeknight pasta.");

    ArgumentCaptor<RecipeBranch> branchCaptor = ArgumentCaptor.forClass(RecipeBranch.class);
    verify(branchRepository).saveAndFlush(branchCaptor.capture());
    assertThat(branchCaptor.getValue().getCurrentVersion()).isEqualTo(2);

    List<Object> events = publishedEvents(2);
    RecipeVersionCreatedEvent created = onlyEvent(events, RecipeVersionCreatedEvent.class);
    assertThat(created.versionNumber()).isEqualTo(2);
    assertThat(created.traceId()).isEqualTo(mdcTrace);
    RecipeUpdatedEvent updated = onlyEvent(events, RecipeUpdatedEvent.class);
    assertThat(updated.trigger()).isEqualTo(VersionTrigger.MANUAL_EDIT);
    assertThat(updated.traceId()).isEqualTo(mdcTrace);

    verify(stepsSpy, atLeastOnce()).size();
    verify(equipmentSpy, atLeastOnce()).size();
    verify(mealTypesSpy, atLeastOnce()).size();
    verify(flavourSpy, atLeastOnce()).size();
    verify(dietarySpy, atLeastOnce()).size();
  }

  @Test
  void manualEdit_staleOptimisticVersion_throwsWithoutWriting() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(
            () ->
                service().manualEdit(recipeId, RecipeTestData.defaultManualEditRequest(7), userId))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verify(versionRepository, never()).saveAndFlush(any());
  }

  @Test
  void manualEdit_identicalBody_throwsNoChanges() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion parent = versionWithDefaultBody(parentId, recipe, branch, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findCurrentVersionId(recipeId, branchId, 1))
        .thenReturn(Optional.of(parentId));
    when(versionRepository.findById(parentId)).thenReturn(Optional.of(parent));

    assertThatThrownBy(
            () -> service().manualEdit(recipeId, RecipeTestData.noopManualEditRequest(0), userId))
        .isInstanceOf(NoChangesException.class);
    verify(versionRepository, never()).saveAndFlush(any());
  }

  @Test
  void manualEdit_missingCurrentVersionPointer_throwsIllegalState() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findCurrentVersionId(recipeId, branchId, 1))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service().manualEdit(recipeId, RecipeTestData.defaultManualEditRequest(0), userId))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void manualEdit_missingParentVersionRow_throwsIllegalState() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findCurrentVersionId(recipeId, branchId, 1))
        .thenReturn(Optional.of(parentId));
    when(versionRepository.findById(parentId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service().manualEdit(recipeId, RecipeTestData.defaultManualEditRequest(0), userId))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void manualEdit_missingCurrentBranch_throwsIllegalState() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeVersion parent = versionWithDefaultBody(parentId, recipe, null, 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findCurrentVersionId(recipeId, branchId, 1))
        .thenReturn(Optional.of(parentId));
    when(versionRepository.findById(parentId)).thenReturn(Optional.of(parent));
    when(branchRepository.findById(branchId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service().manualEdit(recipeId, RecipeTestData.defaultManualEditRequest(0), userId))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------------- createBranch ----------------

  @Test
  void createBranch_forksFromBranchPoint_withDerivedFingerprintAndEvents() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID mainBranchId = UUID.randomUUID();
    UUID branchPointId = UUID.randomUUID();

    Recipe recipe = ownedRecipe(recipeId, userId, mainBranchId);
    RecipeBranch mainBranch = branchOf(recipe, mainBranchId, "main", 1);
    RecipeVersion branchPoint = versionWithDefaultBody(branchPointId, recipe, mainBranch, 1);
    List<RecipeIngredient> ingredientSpy = spy(new ArrayList<>(branchPoint.getIngredients()));
    branchPoint.setIngredients(ingredientSpy);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findByRecipeIdAndName(recipeId, "gluten-free-variant"))
        .thenReturn(Optional.empty());
    when(versionRepository.findById(branchPointId)).thenReturn(Optional.of(branchPoint));
    when(branchRepository.saveAndFlush(any(RecipeBranch.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RecipeBranchDto dto =
        service()
            .createBranch(
                recipeId, RecipeTestData.defaultCreateBranchRequest(branchPointId), userId);

    assertThat(dto).isNotNull();
    assertThat(dto.name()).isEqualTo("gluten-free-variant");
    assertThat(dto.label()).isEqualTo("Gluten-free variant");
    assertThat(dto.currentVersion()).isEqualTo(1);
    assertThat(dto.parentBranchId()).isEqualTo(mainBranchId);
    assertThat(dto.branchPointVersionId()).isEqualTo(branchPointId);
    assertThat(dto.divergenceScore()).isNotNull();

    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    RecipeVersion v1 = versionCaptor.getValue();
    assertThat(v1.getVersionNumber()).isEqualTo(1);
    assertThat(v1.getParentVersionId()).isEqualTo(branchPointId);
    assertThat(v1.getTrigger()).isEqualTo(VersionTrigger.BRANCH_CREATION);
    assertThat(v1.getCharacterFingerprint()).isNotNull();
    assertThat(v1.getIngredients()).hasSize(3);
    assertThat(v1.getMethodSteps()).hasSize(3);
    assertThat(v1.getMetadata()).isNotNull();
    assertThat(v1.getTags()).isNotNull();

    List<Object> events = publishedEvents(2);
    RecipeVersionCreatedEvent created = onlyEvent(events, RecipeVersionCreatedEvent.class);
    assertThat(created.versionNumber()).isEqualTo(1);
    assertThat(created.traceId()).isNotNull();
    RecipeBranchCreatedEvent branchCreated = onlyEvent(events, RecipeBranchCreatedEvent.class);
    assertThat(branchCreated.branchPointVersionId()).isEqualTo(branchPointId);
    assertThat(branchCreated.divergenceScore()).isNotNull();

    verify(ingredientSpy, atLeastOnce()).size();
  }

  @Test
  void createBranch_fingerprintOverride_isPersistedVerbatim() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID mainBranchId = UUID.randomUUID();
    UUID branchPointId = UUID.randomUUID();

    Recipe recipe = ownedRecipe(recipeId, userId, mainBranchId);
    RecipeBranch mainBranch = branchOf(recipe, mainBranchId, "main", 1);
    RecipeVersion branchPoint = versionWithDefaultBody(branchPointId, recipe, mainBranch, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findByRecipeIdAndName(recipeId, "spicy-variant"))
        .thenReturn(Optional.empty());
    when(versionRepository.findById(branchPointId)).thenReturn(Optional.of(branchPoint));
    when(branchRepository.saveAndFlush(any(RecipeBranch.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CharacterFingerprintDto override =
        new CharacterFingerprintDto(
            List.of("Chilli"),
            List.of(),
            List.of(),
            List.of("hot"),
            Complexity.INVOLVED,
            "override-cuisine");
    service()
        .createBranch(
            recipeId, RecipeTestData.branchRequestWithOverride(branchPointId, override), userId);

    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getCharacterFingerprint().get("cuisineAnchor").asText())
        .isEqualTo("override-cuisine");
  }

  @Test
  void createBranch_mainName_isReserved() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(
            () ->
                service()
                    .createBranch(
                        recipeId,
                        RecipeTestData.branchRequestWithName("main", UUID.randomUUID()),
                        userId))
        .isInstanceOf(RecipeBranchNameReservedException.class);
  }

  @Test
  void createBranch_duplicateName_conflicts() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findByRecipeIdAndName(recipeId, "gluten-free-variant"))
        .thenReturn(Optional.of(branchOf(recipe, UUID.randomUUID(), "gluten-free-variant", 1)));

    assertThatThrownBy(
            () ->
                service()
                    .createBranch(
                        recipeId,
                        RecipeTestData.defaultCreateBranchRequest(UUID.randomUUID()),
                        userId))
        .isInstanceOf(RecipeBranchNameConflictException.class);
  }

  @Test
  void createBranch_branchPointBelongingToOtherRecipe_isInvalid() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchPointId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    Recipe other = ownedRecipe(UUID.randomUUID(), userId, UUID.randomUUID());
    RecipeVersion foreignVersion = versionWithDefaultBody(branchPointId, other, null, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findByRecipeIdAndName(recipeId, "gluten-free-variant"))
        .thenReturn(Optional.empty());
    when(versionRepository.findById(branchPointId)).thenReturn(Optional.of(foreignVersion));

    assertThatThrownBy(
            () ->
                service()
                    .createBranch(
                        recipeId, RecipeTestData.defaultCreateBranchRequest(branchPointId), userId))
        .isInstanceOf(RecipeBranchPointInvalidException.class);
  }

  // ---------------- revertToVersion ----------------

  @Test
  void revert_clonesTargetBody_intoNextVersionNumber_andMovesBothPointers() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    recipe.setCurrentVersion(2);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 2);
    RecipeVersion target = versionWithTofuBody(UUID.randomUUID(), recipe, branch, 1);
    RecipeVersion current = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 2);
    List<RecipeIngredient> targetSpy = spy(new ArrayList<>(target.getIngredients()));
    target.setIngredients(targetSpy);
    List<RecipeIngredient> currentSpy = spy(new ArrayList<>(current.getIngredients()));
    current.setIngredients(currentSpy);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 1))
        .thenReturn(Optional.of(target));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 2))
        .thenReturn(Optional.of(current));
    stubIdentitySaves();

    RecipeVersionDto dto = service().revertToVersion(recipeId, branchId, 1, userId, 0);

    assertThat(dto).isNotNull();
    assertThat(dto.versionNumber()).isEqualTo(3);
    assertThat(dto.trigger()).isEqualTo(VersionTrigger.REVERT);
    assertThat(dto.parentVersionId()).isEqualTo(current.getId());

    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    RecipeVersion saved = versionCaptor.getValue();
    assertThat(saved.getIngredients())
        .extracting(RecipeIngredient::getIngredientMappingKey)
        .containsExactly("spaghetti.dry", "tofu.firm");
    assertThat(saved.getMethodSteps())
        .extracting(RecipeMethodStep::getInstruction)
        .containsExactly("Press the tofu.", "Simmer the sauce.");
    assertThat(saved.getMetadata().getServings()).isEqualTo(2);
    assertThat(saved.getMetadata().getEquipmentRequired()).containsExactly("wok");
    assertThat(saved.getMetadata().getMealTypes()).containsExactly("LUNCH");
    assertThat(saved.getMetadata().isPackable()).isTrue();
    assertThat(saved.getMetadata().getCuisine()).isEqualTo("Fusion");
    assertThat(saved.getTags().getProtein()).isEqualTo("tofu");
    assertThat(saved.getTags().getComplexity()).isEqualTo(Complexity.MINIMAL);
    assertThat(saved.getTags().getFlavourProfile()).containsExactly("savoury");
    assertThat(saved.getTags().getDietaryFlags()).containsExactly("vegan");

    ArgumentCaptor<RecipeBranch> branchCaptor = ArgumentCaptor.forClass(RecipeBranch.class);
    verify(branchRepository).saveAndFlush(branchCaptor.capture());
    assertThat(branchCaptor.getValue().getCurrentVersion()).isEqualTo(3);

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getCurrentVersion()).isEqualTo(3);

    List<Object> events = publishedEvents(2);
    assertThat(onlyEvent(events, RecipeVersionCreatedEvent.class).versionNumber()).isEqualTo(3);
    assertThat(onlyEvent(events, RecipeUpdatedEvent.class).trigger())
        .isEqualTo(VersionTrigger.REVERT);

    verify(targetSpy, atLeastOnce()).size();
    verify(currentSpy, atLeastOnce()).size();
  }

  @Test
  void revert_onNonCurrentBranch_leavesRecipePointerAlone() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    RecipeBranch branch = branchOf(recipe, branchId, "variant", 2);
    RecipeVersion target = versionWithTofuBody(UUID.randomUUID(), recipe, branch, 1);
    RecipeVersion current = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 2);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 1))
        .thenReturn(Optional.of(target));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 2))
        .thenReturn(Optional.of(current));
    stubIdentitySaves();

    service().revertToVersion(recipeId, branchId, 1, userId, 0);

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getCurrentVersion()).isEqualTo(1);
  }

  @Test
  void revert_staleOptimisticVersion_throws() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().revertToVersion(recipeId, UUID.randomUUID(), 1, userId, 9))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void revert_branchOfOtherRecipe_throwsBranchNotFound() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    Recipe other = ownedRecipe(UUID.randomUUID(), userId, UUID.randomUUID());
    RecipeBranch foreignBranch = branchOf(other, branchId, "main", 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(foreignBranch));

    assertThatThrownBy(() -> service().revertToVersion(recipeId, branchId, 1, userId, 0))
        .isInstanceOf(RecipeBranchNotFoundException.class);
  }

  @Test
  void revert_toCurrentVersion_throwsNoChanges() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 2);
    RecipeVersion target = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 2);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 2))
        .thenReturn(Optional.of(target));

    assertThatThrownBy(() -> service().revertToVersion(recipeId, branchId, 2, userId, 0))
        .isInstanceOf(NoChangesException.class);
  }

  @Test
  void revert_missingTargetVersion_throwsVersionNotFound() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 2);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 1))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().revertToVersion(recipeId, branchId, 1, userId, 0))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void revert_missingCurrentVersionRow_throwsIllegalState() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 2);
    RecipeVersion target = versionWithTofuBody(UUID.randomUUID(), recipe, branch, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 1))
        .thenReturn(Optional.of(target));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 2))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().revertToVersion(recipeId, branchId, 1, userId, 0))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------------- promoteSubstitutionToVersion ----------------

  @Test
  void promoteSubstitution_appliesOverlay_supersedesSub_andMovesPointers() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID baseVersionId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();

    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion base = versionWithDefaultBody(baseVersionId, recipe, branch, 1);
    List<RecipeIngredient> baseSpy = spy(new ArrayList<>(base.getIngredients()));
    base.setIngredients(baseSpy);
    RecipeSubstitution sub =
        beefToSoySub(subId, recipeId, baseVersionId, branchId, SubstitutionState.ACCEPTED);

    when(substitutionRepository.findById(subId)).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(baseVersionId)).thenReturn(Optional.of(base));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    stubIdentitySaves();
    when(substitutionRepository.saveAndFlush(any(RecipeSubstitution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RecipeVersionDto dto =
        service().promoteSubstitutionToVersion(subId, userId, 0, "Keep the soy swap.");

    assertThat(dto).isNotNull();
    assertThat(dto.versionNumber()).isEqualTo(2);
    assertThat(dto.trigger()).isEqualTo(VersionTrigger.SUBSTITUTION_PROMOTION);

    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    RecipeVersion saved = versionCaptor.getValue();
    assertThat(saved.getParentVersionId()).isEqualTo(baseVersionId);
    assertThat(saved.getIngredients())
        .extracting(RecipeIngredient::getIngredientMappingKey)
        .contains("soy.crumble")
        .doesNotContain("beef.mince");
    assertThat(saved.getMethodSteps()).hasSize(3);
    assertThat(saved.getMetadata()).isNotNull();
    assertThat(saved.getTags()).isNotNull();

    ArgumentCaptor<RecipeBranch> branchCaptor = ArgumentCaptor.forClass(RecipeBranch.class);
    verify(branchRepository).saveAndFlush(branchCaptor.capture());
    assertThat(branchCaptor.getValue().getCurrentVersion()).isEqualTo(2);

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getCurrentVersion()).isEqualTo(2);

    ArgumentCaptor<RecipeSubstitution> subCaptor =
        ArgumentCaptor.forClass(RecipeSubstitution.class);
    verify(substitutionRepository).saveAndFlush(subCaptor.capture());
    assertThat(subCaptor.getValue().getState()).isEqualTo(SubstitutionState.SUPERSEDED);
    assertThat(subCaptor.getValue().getPromotedToVersionId()).isEqualTo(dto.id());

    List<Object> events = publishedEvents(3);
    assertThat(onlyEvent(events, RecipeVersionCreatedEvent.class).versionNumber()).isEqualTo(2);
    assertThat(onlyEvent(events, RecipeUpdatedEvent.class).trigger())
        .isEqualTo(VersionTrigger.SUBSTITUTION_PROMOTION);
    RecipeSubstitutionStateChangedEvent stateChanged =
        onlyEvent(events, RecipeSubstitutionStateChangedEvent.class);
    assertThat(stateChanged.previousState()).isEqualTo(SubstitutionState.ACCEPTED);
    assertThat(stateChanged.newState()).isEqualTo(SubstitutionState.SUPERSEDED);

    verify(baseSpy, atLeastOnce()).size();
  }

  @Test
  void promoteSubstitution_onNonCurrentBranch_recipePointerUntouched() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID baseVersionId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();

    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    RecipeBranch branch = branchOf(recipe, branchId, "variant", 1);
    RecipeVersion base = versionWithDefaultBody(baseVersionId, recipe, branch, 1);
    RecipeSubstitution sub =
        beefToSoySub(subId, recipeId, baseVersionId, branchId, SubstitutionState.ACCEPTED);

    when(substitutionRepository.findById(subId)).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(baseVersionId)).thenReturn(Optional.of(base));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.saveAndFlush(any(RecipeBranch.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(substitutionRepository.saveAndFlush(any(RecipeSubstitution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().promoteSubstitutionToVersion(subId, userId, 0, null);

    verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
  }

  @Test
  void promoteSubstitution_missingBaseVersion_throwsVersionNotFound() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    UUID baseVersionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    RecipeSubstitution sub =
        beefToSoySub(subId, recipeId, baseVersionId, UUID.randomUUID(), SubstitutionState.ACCEPTED);

    when(substitutionRepository.findById(subId)).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(baseVersionId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().promoteSubstitutionToVersion(subId, userId, 0, null))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void promoteSubstitution_missingBranch_throwsBranchNotFound() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    UUID baseVersionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeVersion base = versionWithDefaultBody(baseVersionId, recipe, null, 1);
    RecipeSubstitution sub =
        beefToSoySub(subId, recipeId, baseVersionId, branchId, SubstitutionState.ACCEPTED);

    when(substitutionRepository.findById(subId)).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(baseVersionId)).thenReturn(Optional.of(base));
    when(branchRepository.findById(branchId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().promoteSubstitutionToVersion(subId, userId, 0, null))
        .isInstanceOf(RecipeBranchNotFoundException.class);
  }

  // ---------------- createSubstitution ----------------

  @Test
  void createSubstitution_persistsProposedRow_withMethodOverlay() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion version = versionWithDefaultBody(versionId, recipe, branch, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(ingredientRepository.findMappingKeysByVersionId(versionId))
        .thenReturn(List.of("spaghetti.dry", "beef.mince", "tomato.passata"));
    when(substitutionRepository.saveAndFlush(any(RecipeSubstitution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RecipeSubstitutionDto dto =
        service()
            .createSubstitution(
                recipeId, RecipeTestData.substitutionRequestWithMethodOverlay(versionId), userId);

    assertThat(dto).isNotNull();
    assertThat(dto.id()).isNotNull();

    ArgumentCaptor<RecipeSubstitution> subCaptor =
        ArgumentCaptor.forClass(RecipeSubstitution.class);
    verify(substitutionRepository).saveAndFlush(subCaptor.capture());
    RecipeSubstitution saved = subCaptor.getValue();
    assertThat(saved.getState()).isEqualTo(SubstitutionState.PROPOSED);
    assertThat(saved.getBranchId()).isEqualTo(branchId);
    assertThat(saved.getOriginalMappingKey()).isEqualTo("beef.mince");
    assertThat(saved.getSubstituteMappingKey()).isEqualTo("soy.crumble");
    assertThat(saved.getMethodOverlay()).hasSize(1);
    assertThat(saved.getMethodOverlay().get(0).step()).isEqualTo(2);

    List<Object> events = publishedEvents(1);
    RecipeSubstitutionCreatedEvent event = onlyEvent(events, RecipeSubstitutionCreatedEvent.class);
    assertThat(event.substitutionId()).isEqualTo(saved.getId());
    assertThat(event.recipeId()).isEqualTo(recipeId);
    assertThat(event.versionId()).isEqualTo(versionId);
    assertThat(event.branchId()).isEqualTo(branchId);
    assertThat(event.reason()).isEqualTo(SubstitutionReason.DIETARY_TEMP);
  }

  @Test
  void createSubstitution_withoutOverlay_persistsNullOverlay() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    RecipeVersion version = versionWithDefaultBody(versionId, recipe, null, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(ingredientRepository.findMappingKeysByVersionId(versionId))
        .thenReturn(List.of("beef.mince"));
    when(substitutionRepository.saveAndFlush(any(RecipeSubstitution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service()
        .createSubstitution(recipeId, RecipeTestData.defaultSubstitutionRequest(versionId), userId);

    ArgumentCaptor<RecipeSubstitution> subCaptor =
        ArgumentCaptor.forClass(RecipeSubstitution.class);
    verify(substitutionRepository).saveAndFlush(subCaptor.capture());
    assertThat(subCaptor.getValue().getMethodOverlay()).isNull();
    assertThat(subCaptor.getValue().getBranchId()).isNull();
  }

  @Test
  void createSubstitution_originalNotInVersion_throws() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    RecipeVersion version = versionWithDefaultBody(versionId, recipe, null, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(ingredientRepository.findMappingKeysByVersionId(versionId))
        .thenReturn(List.of("spaghetti.dry"));

    assertThatThrownBy(
            () ->
                service()
                    .createSubstitution(
                        recipeId, RecipeTestData.defaultSubstitutionRequest(versionId), userId))
        .isInstanceOf(SubstitutionOriginalNotInVersionException.class);
  }

  @Test
  void createSubstitution_versionOfOtherRecipe_throwsVersionNotFound() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    Recipe other = ownedRecipe(UUID.randomUUID(), userId, UUID.randomUUID());
    RecipeVersion foreignVersion = versionWithDefaultBody(versionId, other, null, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(foreignVersion));

    assertThatThrownBy(
            () ->
                service()
                    .createSubstitution(
                        recipeId, RecipeTestData.defaultSubstitutionRequest(versionId), userId))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  // ---------------- rejectSubstitution ----------------

  @Test
  void rejectSubstitution_transitionsState_andPublishesEvent() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    RecipeSubstitution sub =
        beefToSoySub(subId, recipeId, UUID.randomUUID(), null, SubstitutionState.PROPOSED);

    when(substitutionRepository.findById(subId)).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(substitutionRepository.saveAndFlush(any(RecipeSubstitution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RecipeSubstitutionDto dto = service().rejectSubstitution(subId, userId, 0, null);

    assertThat(dto).isNotNull();
    assertThat(dto.state()).isEqualTo(SubstitutionState.REJECTED);

    ArgumentCaptor<RecipeSubstitution> subCaptor =
        ArgumentCaptor.forClass(RecipeSubstitution.class);
    verify(substitutionRepository).saveAndFlush(subCaptor.capture());
    assertThat(subCaptor.getValue().getState()).isEqualTo(SubstitutionState.REJECTED);

    List<Object> events = publishedEvents(1);
    RecipeSubstitutionStateChangedEvent event =
        onlyEvent(events, RecipeSubstitutionStateChangedEvent.class);
    assertThat(event.previousState()).isEqualTo(SubstitutionState.PROPOSED);
    assertThat(event.newState()).isEqualTo(SubstitutionState.REJECTED);
  }

  @Test
  void rejectSubstitution_alreadyRejected_isNoopReturningDto() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    RecipeSubstitution sub =
        beefToSoySub(subId, recipeId, UUID.randomUUID(), null, SubstitutionState.REJECTED);

    when(substitutionRepository.findById(subId)).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));

    RecipeSubstitutionDto dto = service().rejectSubstitution(subId, userId, 0, "why not");

    assertThat(dto).isNotNull();
    assertThat(dto.state()).isEqualTo(SubstitutionState.REJECTED);
    verify(substitutionRepository, never()).saveAndFlush(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------------- discovery import ----------------

  private static ImportedRecipeData importedData(
      ImportedRecipeData.ImportedRecipeMetadata metadata,
      ImportedRecipeData.ImportedRecipeTags tags) {
    return new ImportedRecipeData(
        "bbc_good_food",
        "https://example.test/r/1",
        "fp-1",
        "Imported Curry",
        "desc",
        List.of(
            new ImportedRecipeData.ImportedIngredient(
                0, "Chicken", "chicken.breast", BigDecimal.TEN, "g", null, false)),
        List.of(new ImportedRecipeData.ImportedMethodStep(1, "Fry it all.", 5)),
        metadata,
        tags,
        "json_ld",
        new BigDecimal("0.90"),
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  @Test
  void saveImportedRecipe_persistsFullGraph_pointersEventsAndResult() {
    when(importRepository.findByContentFingerprint("fp-1")).thenReturn(Optional.empty());
    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    ImportedRecipeData data =
        importedData(
            new ImportedRecipeData.ImportedRecipeMetadata(
                6, 10, 20, 30, List.of("wok"), 2, 1, Boolean.TRUE, "Thai", List.of("DINNER")),
            new ImportedRecipeData.ImportedRecipeTags(
                "chicken", "wok", "moderate", List.of("spicy"), List.of("dairy_free")));

    ImportedRecipeResult result = service().saveImportedRecipe(data);

    assertThat(result).isNotNull();
    assertThat(result.newlyCreated()).isTrue();
    assertThat(result.recipeId()).isNotNull();
    assertThat(result.versionId()).isNotNull();
    assertThat(result.dedupReason()).isNull();

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getCurrentBranchId()).isNotNull();
    assertThat(recipeCaptor.getValue().getCatalogue()).isEqualTo(Catalogue.SYSTEM);

    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    RecipeVersion version = versionCaptor.getValue();
    assertThat(version.getMethodSteps()).hasSize(1);
    assertThat(version.getMethodSteps().get(0).getInstruction()).isEqualTo("Fry it all.");
    assertThat(version.getMetadata()).isNotNull();
    assertThat(version.getMetadata().getServings()).isEqualTo(6);
    assertThat(version.getMetadata().getPrepTimeMins()).isEqualTo(10);
    assertThat(version.getMetadata().getCookTimeMins()).isEqualTo(20);
    assertThat(version.getMetadata().getTotalTimeMins()).isEqualTo(30);
    assertThat(version.getMetadata().getEquipmentRequired()).containsExactly("wok");
    assertThat(version.getMetadata().isPackable()).isTrue();
    assertThat(version.getMetadata().getCuisine()).isEqualTo("Thai");
    assertThat(version.getMetadata().getMealTypes()).containsExactly("DINNER");
    assertThat(version.getTags()).isNotNull();
    assertThat(version.getTags().getProtein()).isEqualTo("chicken");
    assertThat(version.getTags().getComplexity()).isEqualTo(Complexity.MODERATE);
    assertThat(version.getTags().getFlavourProfile()).containsExactly("spicy");
    assertThat(version.getTags().getDietaryFlags()).containsExactly("dairy_free");

    List<Object> events = publishedEvents(2);
    RecipeCreatedEvent created = onlyEvent(events, RecipeCreatedEvent.class);
    assertThat(created.catalogue()).isEqualTo(Catalogue.SYSTEM);
    assertThat(created.dataQuality()).isEqualTo(DataQuality.WEB_DISCOVERED);
    assertThat(created.traceId()).isEqualTo(data.traceId());
    assertThat(onlyEvent(events, RecipeVersionCreatedEvent.class).versionNumber()).isEqualTo(1);
  }

  @Test
  void saveImportedRecipe_nullMetadataAndTags_writesSafeDefaults() {
    when(importRepository.findByContentFingerprint("fp-1")).thenReturn(Optional.empty());
    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    service().saveImportedRecipe(importedData(null, null));

    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    RecipeVersion version = versionCaptor.getValue();
    assertThat(version.getMetadata()).isNotNull();
    assertThat(version.getMetadata().getServings()).isZero();
    assertThat(version.getMetadata().getEquipmentRequired()).isEmpty();
    assertThat(version.getMetadata().isPackable()).isFalse();
    assertThat(version.getTags()).isNotNull();
    assertThat(version.getTags().getComplexity()).isNull();
    assertThat(version.getTags().getFlavourProfile()).isEmpty();
    assertThat(version.getTags().getDietaryFlags()).isEmpty();
  }

  @Test
  void saveImportedRecipe_fingerprintMatch_returnsExistingWithCurrentVersionId() {
    UUID existingRecipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe existing = systemRecipe(existingRecipeId);
    existing.setCurrentBranchId(branchId);
    RecipeVersion version = bareVersion(versionId, existing, null, 1);

    when(importRepository.findByContentFingerprint("fp-1"))
        .thenReturn(
            Optional.of(
                RecipeImport.builder()
                    .id(UUID.randomUUID())
                    .recipeId(existingRecipeId)
                    .contentFingerprint("fp-1")
                    .build()));
    when(recipeRepository.findByIdAndDeletedAtIsNull(existingRecipeId))
        .thenReturn(Optional.of(existing));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(
            existingRecipeId, branchId, 1))
        .thenReturn(Optional.of(version));

    ImportedRecipeResult result = service().saveImportedRecipe(importedData(null, null));

    assertThat(result).isNotNull();
    assertThat(result.newlyCreated()).isFalse();
    assertThat(result.recipeId()).isEqualTo(existingRecipeId);
    assertThat(result.versionId()).isEqualTo(versionId);
    assertThat(result.dedupReason()).contains(existingRecipeId.toString());
    verify(recipeRepository, never()).save(any(Recipe.class));
  }

  @Test
  void saveImportedRecipe_fingerprintMatch_existingWithoutBranch_hasNullVersionId() {
    UUID existingRecipeId = UUID.randomUUID();
    Recipe existing = systemRecipe(existingRecipeId);

    when(importRepository.findByContentFingerprint("fp-1"))
        .thenReturn(
            Optional.of(
                RecipeImport.builder()
                    .id(UUID.randomUUID())
                    .recipeId(existingRecipeId)
                    .contentFingerprint("fp-1")
                    .build()));
    when(recipeRepository.findByIdAndDeletedAtIsNull(existingRecipeId))
        .thenReturn(Optional.of(existing));

    ImportedRecipeResult result = service().saveImportedRecipe(importedData(null, null));

    assertThat(result.versionId()).isNull();
    assertThat(result.newlyCreated()).isFalse();
  }

  // ---------------- import preview / confirm ----------------

  private static ParsedRecipe extractedRecipe(
      String url, Integer servings, ExtractionProvenance provenance) {
    return new ParsedRecipe(
        url,
        "Preview Pasta",
        "Quick bowl.",
        List.of(ParsedRecipe.ParsedIngredient.ofLine("spaghetti")),
        List.of(new ParsedRecipe.ParsedMethodStep(1, "Boil the pasta.")),
        10,
        15,
        25,
        servings,
        "Italian",
        provenance);
  }

  @Test
  void previewFromHtml_extractsCandidate_withProvenanceLabel_andNoWarnings() {
    UUID userId = UUID.randomUUID();
    String url = "https://example.test/r";
    when(extractionService.extract(any()))
        .thenReturn(
            Optional.of(
                extractedRecipe(
                    url,
                    4,
                    new ExtractionProvenance(
                        ExtractionLayer.JSON_LD, List.of(ExtractionLayer.JSON_LD)))));
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());

    RecipeImportPreview preview =
        service().previewImportFromHtml(userId, new ImportRecipeFromHtmlRequest(url, "<html/>"));

    assertThat(preview).isNotNull();
    assertThat(preview.parsedRecipe().name()).isEqualTo("Preview Pasta");
    assertThat(preview.sourceUrl()).isEqualTo(url);
    assertThat(preview.extractionMethod()).isEqualTo("json_ld");
    assertThat(preview.validationWarnings()).isEmpty();
    assertThat(preview.dedupCandidate()).isNull();
    assertThat(preview.previewToken()).isNotBlank();
  }

  @Test
  void previewFromHtml_missingServings_flagsServingsDefaulted() {
    UUID userId = UUID.randomUUID();
    String url = "https://example.test/r";
    when(extractionService.extract(any()))
        .thenReturn(
            Optional.of(
                extractedRecipe(
                    url,
                    null,
                    new ExtractionProvenance(
                        ExtractionLayer.JSON_LD, List.of(ExtractionLayer.JSON_LD)))));
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());

    RecipeImportPreview preview =
        service().previewImportFromHtml(userId, new ImportRecipeFromHtmlRequest(url, "<html/>"));

    assertThat(preview.validationWarnings()).containsExactly("servings_defaulted");
  }

  @Test
  void previewFromHtml_zeroServings_alsoFlagsServingsDefaulted() {
    UUID userId = UUID.randomUUID();
    String url = "https://example.test/r";
    when(extractionService.extract(any()))
        .thenReturn(
            Optional.of(
                extractedRecipe(
                    url,
                    0,
                    new ExtractionProvenance(
                        ExtractionLayer.JSON_LD, List.of(ExtractionLayer.JSON_LD)))));
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());

    RecipeImportPreview preview =
        service().previewImportFromHtml(userId, new ImportRecipeFromHtmlRequest(url, "<html/>"));

    assertThat(preview.validationWarnings()).containsExactly("servings_defaulted");
  }

  @Test
  void previewFromHtml_microdataDetail_winsOverGenericLabel() {
    UUID userId = UUID.randomUUID();
    String url = "https://example.test/r";
    when(extractionService.extract(any()))
        .thenReturn(
            Optional.of(
                extractedRecipe(
                    url,
                    4,
                    new ExtractionProvenance(
                        ExtractionLayer.MICRODATA,
                        List.of(ExtractionLayer.MICRODATA),
                        "common_selectors"))));
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());

    RecipeImportPreview preview =
        service().previewImportFromHtml(userId, new ImportRecipeFromHtmlRequest(url, "<html/>"));

    assertThat(preview.extractionMethod()).isEqualTo("common_selectors");
  }

  @Test
  void previewFromHtml_microdataWithoutDetail_labelsMicrodata() {
    UUID userId = UUID.randomUUID();
    String url = "https://example.test/r";
    when(extractionService.extract(any()))
        .thenReturn(
            Optional.of(
                extractedRecipe(
                    url,
                    4,
                    new ExtractionProvenance(
                        ExtractionLayer.MICRODATA, List.of(ExtractionLayer.MICRODATA)))));
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());

    RecipeImportPreview preview =
        service().previewImportFromHtml(userId, new ImportRecipeFromHtmlRequest(url, "<html/>"));

    assertThat(preview.extractionMethod()).isEqualTo("microdata");
  }

  @Test
  void previewFromHtml_missingProvenance_leavesExtractionMethodNull() {
    UUID userId = UUID.randomUUID();
    String url = "https://example.test/r";
    when(extractionService.extract(any())).thenReturn(Optional.of(extractedRecipe(url, 4, null)));
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());

    RecipeImportPreview preview =
        service().previewImportFromHtml(userId, new ImportRecipeFromHtmlRequest(url, "<html/>"));

    assertThat(preview).isNotNull();
    assertThat(preview.extractionMethod()).isNull();
  }

  @Test
  void previewFromHtml_noExtractorMatched_throwsImportFailure() {
    when(extractionService.extract(any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .previewImportFromHtml(
                        UUID.randomUUID(),
                        new ImportRecipeFromHtmlRequest("https://example.test/r", "<html/>")))
        .isInstanceOf(RecipeImportFailureException.class);
  }

  @Test
  void previewFromHtml_nearDuplicate_attachesDedupCandidate() {
    UUID userId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    String url = "https://example.test/r";
    when(extractionService.extract(any()))
        .thenReturn(
            Optional.of(
                extractedRecipe(
                    url,
                    4,
                    new ExtractionProvenance(
                        ExtractionLayer.JSON_LD, List.of(ExtractionLayer.JSON_LD)))));
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.<Object[]>of(new Object[] {candidateId, "imported.spaghetti", 1L}));

    RecipeImportPreview preview =
        service().previewImportFromHtml(userId, new ImportRecipeFromHtmlRequest(url, "<html/>"));

    assertThat(preview.dedupCandidate()).isNotNull();
    assertThat(preview.dedupCandidate().recipeId()).isEqualTo(candidateId);
    assertThat(preview.dedupCandidate().ingredientOverlap()).isEqualTo(1.0);
  }

  @Test
  void previewFromUrl_fetchesMarkup_thenBuildsPreview() {
    UUID userId = UUID.randomUUID();
    String url = "https://example.test/r";
    when(urlFetcher.fetch(url)).thenReturn("<html/>");
    when(extractionService.extract(any()))
        .thenReturn(
            Optional.of(
                extractedRecipe(
                    url,
                    4,
                    new ExtractionProvenance(
                        ExtractionLayer.JSON_LD, List.of(ExtractionLayer.JSON_LD)))));
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());

    RecipeImportPreview preview =
        service().previewImportFromUrl(userId, new ImportRecipeFromUrlRequest(url, null));

    assertThat(preview).isNotNull();
    assertThat(preview.sourceUrl()).isEqualTo(url);
    verify(urlFetcher).fetch(url);
  }

  private void stubImportPersistence() {
    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(importRepository.save(any(RecipeImport.class))).thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.findByIdAndDeletedAtIsNull(any(UUID.class))).thenReturn(Optional.empty());
  }

  private void stubDedupCollision(UUID userId, UUID candidateId) {
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(
            List.of(
                new Object[] {candidateId, "spaghetti.dry", 3L},
                new Object[] {candidateId, "beef.mince", 3L},
                new Object[] {candidateId, "tomato.passata", 3L}));
  }

  @Test
  void confirmImport_honouredOverride_recordsDuplicateOnProvenance() {
    UUID userId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    stubDedupCollision(userId, candidateId);
    stubImportPersistence();

    RecipeDto dto =
        service()
            .confirmImport(
                userId,
                new ConfirmImportRequest(
                    "tok",
                    "https://example.test/r",
                    "json_ld",
                    RecipeTestData.defaultCreateRequest(),
                    candidateId));

    assertThat(dto).isNotNull();
    ArgumentCaptor<RecipeImport> importCaptor = ArgumentCaptor.forClass(RecipeImport.class);
    verify(importRepository).save(importCaptor.capture());
    RecipeImport provenance = importCaptor.getValue();
    assertThat(provenance.getDuplicateOfRecipeId()).isEqualTo(candidateId);
    assertThat(provenance.getExtractionMethod()).isEqualTo("json_ld");
    assertThat(provenance.getSourceType()).isEqualTo(ImportSource.URL);
    assertThat(provenance.getSourceUrl()).isEqualTo("https://example.test/r");
  }

  @Test
  void confirmImport_nestedOverrideFallback_isHonoured() {
    UUID userId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    stubDedupCollision(userId, candidateId);
    stubImportPersistence();

    var base = RecipeTestData.defaultCreateRequest();
    var withNestedOverride =
        new com.example.mealprep.recipe.api.dto.CreateRecipeRequest(
            base.name(),
            base.description(),
            base.ingredients(),
            base.method(),
            base.metadata(),
            base.tags(),
            candidateId);

    RecipeDto dto =
        service()
            .confirmImport(
                userId,
                new ConfirmImportRequest(
                    "tok", "https://example.test/r", "json_ld", withNestedOverride, null));

    assertThat(dto).isNotNull();
    ArgumentCaptor<RecipeImport> importCaptor = ArgumentCaptor.forClass(RecipeImport.class);
    verify(importRepository).save(importCaptor.capture());
    assertThat(importCaptor.getValue().getDuplicateOfRecipeId()).isEqualTo(candidateId);
  }

  @Test
  void confirmImport_blankExtractionMethod_defaultsToUserConfirmed() {
    UUID userId = UUID.randomUUID();
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());
    stubImportPersistence();

    service()
        .confirmImport(
            userId,
            new ConfirmImportRequest(
                "tok", "https://example.test/r", "  ", RecipeTestData.defaultCreateRequest()));

    ArgumentCaptor<RecipeImport> importCaptor = ArgumentCaptor.forClass(RecipeImport.class);
    verify(importRepository).save(importCaptor.capture());
    assertThat(importCaptor.getValue().getExtractionMethod()).isEqualTo("user_confirmed");
    assertThat(importCaptor.getValue().getDuplicateOfRecipeId()).isNull();
  }

  // ---------------- planner reads ----------------

  @Test
  void findPlannableCandidatesByKind_guardInputs_returnEmptyWithoutQuerying() {
    assertThat(service().findPlannableCandidatesByKind(null, "DINNER", 5, null)).isEmpty();
    assertThat(service().findPlannableCandidatesByKind(UUID.randomUUID(), null, 5, null)).isEmpty();
    assertThat(service().findPlannableCandidatesByKind(UUID.randomUUID(), "  ", 5, null)).isEmpty();
    assertThat(service().findPlannableCandidatesByKind(UUID.randomUUID(), "DINNER", 0, null))
        .isEmpty();

    verify(recipeRepository, never()).findPlannableByKind(any(), any(), anyInt());
    verify(recipeRepository, never())
        .findPlannableByKindRankedByTaste(any(), any(), any(), anyInt());
  }

  @Test
  void findPlannableCandidatesByKind_withTasteVector_usesRankedQuery() {
    UUID userId = UUID.randomUUID();
    Recipe r1 = ownedRecipe(UUID.randomUUID(), userId, null);
    Recipe r2 = ownedRecipe(UUID.randomUUID(), userId, null);
    when(recipeRepository.findPlannableByKindRankedByTaste(userId, "DINNER", "[0.1,0.2]", 5))
        .thenReturn(List.of(r1, r2));
    when(branchRepository.findAllByRecipeId(any())).thenReturn(List.of());

    List<RecipeDto> result =
        service().findPlannableCandidatesByKind(userId, "DINNER", 5, "[0.1,0.2]");

    assertThat(result).hasSize(2);
    verify(recipeRepository, never()).findPlannableByKind(any(), any(), anyInt());
  }

  @Test
  void findPlannableCandidatesByKind_blankTasteVector_fallsBackToRecencyQuery() {
    UUID userId = UUID.randomUUID();
    Recipe r1 = ownedRecipe(UUID.randomUUID(), userId, null);
    when(recipeRepository.findPlannableByKind(userId, "DINNER", 5)).thenReturn(List.of(r1));
    when(branchRepository.findAllByRecipeId(any())).thenReturn(List.of());

    List<RecipeDto> result = service().findPlannableCandidatesByKind(userId, "DINNER", 5, "  ");

    assertThat(result).hasSize(1);
    verify(recipeRepository, never())
        .findPlannableByKindRankedByTaste(any(), any(), any(), anyInt());
  }

  // ---------------- reads: getById / branches / fingerprint / provenance / diff ----------------

  @Test
  void getById_hydratesBodyAndBranches() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion version = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 1);
    List<RecipeIngredient> ingredientSpy = spy(new ArrayList<>(version.getIngredients()));
    version.setIngredients(ingredientSpy);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findAllByRecipeId(recipeId)).thenReturn(List.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 1))
        .thenReturn(Optional.of(version));

    Optional<RecipeDto> result = service().getById(recipeId);

    assertThat(result).isPresent();
    assertThat(result.get().currentVersionBody()).isNotNull();
    assertThat(result.get().currentVersionBody().ingredients()).hasSize(3);
    assertThat(result.get().branches()).hasSize(1);
    verify(ingredientSpy, atLeastOnce()).size();
  }

  @Test
  void getBranches_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getBranches(recipeId))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void getBranches_returnsMappedBranchList() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeBranch branch = branchOf(recipe, UUID.randomUUID(), "main", 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findAllByRecipeId(recipeId)).thenReturn(List.of(branch));

    List<RecipeBranchDto> branches = service().getBranches(recipeId);

    assertThat(branches).hasSize(1);
    assertThat(branches.get(0).name()).isEqualTo("main");
  }

  @Test
  void getBranch_matchingRecipe_isPresent() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));

    Optional<RecipeBranchDto> result = service().getBranch(recipeId, branchId);

    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("main");
  }

  @Test
  void getBranch_ofOtherRecipe_isEmpty() {
    UUID branchId = UUID.randomUUID();
    Recipe other = ownedRecipe(UUID.randomUUID(), UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(other, branchId, "main", 1);
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));

    assertThat(service().getBranch(UUID.randomUUID(), branchId)).isEmpty();
  }

  @Test
  void getFingerprint_returnsStoredFingerprint() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion version = bareVersion(UUID.randomUUID(), recipe, branch, 1);
    version.setCharacterFingerprint(objectMapper.valueToTree(RecipeTestData.defaultFingerprint()));

    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 1))
        .thenReturn(Optional.of(version));

    Optional<CharacterFingerprintDto> result = service().getFingerprint(recipeId, branchId);

    assertThat(result).isPresent();
    assertThat(result.get().cuisineAnchor()).isEqualTo("Italian");
    assertThat(result.get().complexityTier()).isEqualTo(Complexity.MODERATE);
  }

  @Test
  void getFingerprint_branchOfOtherRecipe_isEmpty() {
    UUID branchId = UUID.randomUUID();
    Recipe other = ownedRecipe(UUID.randomUUID(), UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(other, branchId, "main", 1);
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));

    assertThat(service().getFingerprint(UUID.randomUUID(), branchId)).isEmpty();
    verify(versionRepository, never())
        .findFirstByRecipeIdAndBranchIdAndVersionNumber(any(), any(), anyInt());
  }

  @Test
  void getImportProvenance_present_mapsRow() {
    UUID recipeId = UUID.randomUUID();
    RecipeImport row =
        RecipeImport.builder()
            .id(UUID.randomUUID())
            .recipeId(recipeId)
            .sourceType(ImportSource.URL)
            .sourceUrl("https://example.test/r")
            .importedAt(fixedInstant)
            .importedByUserId(UUID.randomUUID())
            .build();
    when(importRepository.findByRecipeId(recipeId)).thenReturn(Optional.of(row));

    var result = service().getImportProvenance(recipeId);

    assertThat(result).isPresent();
    assertThat(result.get().sourceUrl()).isEqualTo("https://example.test/r");
  }

  @Test
  void diff_returnsPersistedDiffForDirectChild() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 2);
    RecipeVersion from = bareVersion(UUID.randomUUID(), recipe, branch, 1);
    RecipeVersion to = bareVersion(UUID.randomUUID(), recipe, branch, 2);
    to.setParentVersionId(from.getId());

    when(versionRepository.findById(to.getId())).thenReturn(Optional.of(to));
    when(versionRepository.findById(from.getId())).thenReturn(Optional.of(from));

    RecipeDiffDto dto = service().diff(recipeId, from.getId(), to.getId());

    assertThat(dto).isNotNull();
    assertThat(dto.fromVersionId()).isEqualTo(from.getId());
    assertThat(dto.toVersionId()).isEqualTo(to.getId());
  }

  @Test
  void diff_toVersionOfOtherRecipe_throwsVersionNotFound() {
    Recipe other = ownedRecipe(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion to = bareVersion(UUID.randomUUID(), other, null, 2);
    when(versionRepository.findById(to.getId())).thenReturn(Optional.of(to));

    assertThatThrownBy(() -> service().diff(UUID.randomUUID(), UUID.randomUUID(), to.getId()))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void diff_missingFromVersion_throwsVersionNotFound() {
    UUID recipeId = UUID.randomUUID();
    UUID fromId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion to = bareVersion(UUID.randomUUID(), recipe, null, 2);
    when(versionRepository.findById(to.getId())).thenReturn(Optional.of(to));
    when(versionRepository.findById(fromId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().diff(recipeId, fromId, to.getId()))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void diff_acrossBranches_throwsCrossBranch() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeBranch branchA = branchOf(recipe, UUID.randomUUID(), "main", 1);
    RecipeBranch branchB = branchOf(recipe, UUID.randomUUID(), "variant", 1);
    RecipeVersion from = bareVersion(UUID.randomUUID(), recipe, branchA, 1);
    RecipeVersion to = bareVersion(UUID.randomUUID(), recipe, branchB, 1);
    when(versionRepository.findById(to.getId())).thenReturn(Optional.of(to));
    when(versionRepository.findById(from.getId())).thenReturn(Optional.of(from));

    assertThatThrownBy(() -> service().diff(recipeId, from.getId(), to.getId()))
        .isInstanceOf(RecipeDiffCrossBranchException.class);
  }

  @Test
  void diff_fromWithoutBranch_throwsCrossBranch() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeBranch branch = branchOf(recipe, UUID.randomUUID(), "main", 1);
    RecipeVersion from = bareVersion(UUID.randomUUID(), recipe, null, 1);
    RecipeVersion to = bareVersion(UUID.randomUUID(), recipe, branch, 2);
    when(versionRepository.findById(to.getId())).thenReturn(Optional.of(to));
    when(versionRepository.findById(from.getId())).thenReturn(Optional.of(from));

    assertThatThrownBy(() -> service().diff(recipeId, from.getId(), to.getId()))
        .isInstanceOf(RecipeDiffCrossBranchException.class);
  }

  @Test
  void diff_notDirectChild_throwsNotComputed() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeBranch branch = branchOf(recipe, UUID.randomUUID(), "main", 3);
    RecipeVersion from = bareVersion(UUID.randomUUID(), recipe, branch, 1);
    RecipeVersion to = bareVersion(UUID.randomUUID(), recipe, branch, 3);
    to.setParentVersionId(UUID.randomUUID());
    when(versionRepository.findById(to.getId())).thenReturn(Optional.of(to));
    when(versionRepository.findById(from.getId())).thenReturn(Optional.of(from));

    assertThatThrownBy(() -> service().diff(recipeId, from.getId(), to.getId()))
        .isInstanceOf(RecipeDiffNotComputedException.class);
  }

  // ---------------- version history ----------------

  @Test
  void getVersionHistory_returnsPage_forcingChildLoads() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 2);
    RecipeVersion v1 = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 1);
    RecipeVersion v2 = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 2);
    List<RecipeIngredient> spy1 = spy(new ArrayList<>(v1.getIngredients()));
    v1.setIngredients(spy1);
    List<RecipeIngredient> spy2 = spy(new ArrayList<>(v2.getIngredients()));
    v2.setIngredients(spy2);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    Pageable pageable = PageRequest.of(0, 20);
    when(versionRepository.findByRecipeIdAndBranchIdOrderByVersionNumberDesc(
            recipeId, branchId, pageable))
        .thenReturn(new PageImpl<>(List.of(v2, v1), pageable, 2));

    Page<RecipeVersionDto> page = service().getVersionHistory(recipeId, branchId, pageable);

    assertThat(page).isNotNull();
    assertThat(page.getContent()).hasSize(2);
    assertThat(page.getContent().get(0).versionNumber()).isEqualTo(2);
    verify(spy1, atLeastOnce()).size();
    verify(spy2, atLeastOnce()).size();
  }

  @Test
  void getVersionHistory_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().getVersionHistory(recipeId, UUID.randomUUID(), PageRequest.of(0, 20)))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void getVersionHistory_branchOfOtherRecipe_throwsBranchNotFound() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    Recipe other = ownedRecipe(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    RecipeBranch foreignBranch = branchOf(other, branchId, "main", 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(foreignBranch));

    assertThatThrownBy(() -> service().getVersionHistory(recipeId, branchId, PageRequest.of(0, 20)))
        .isInstanceOf(RecipeBranchNotFoundException.class);
  }

  @Test
  void getVersionHistory_missingBranch_throwsBranchNotFound() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getVersionHistory(recipeId, branchId, PageRequest.of(0, 20)))
        .isInstanceOf(RecipeBranchNotFoundException.class);
  }

  @Test
  void getVersionByNumber_returnsMappedVersion() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion version = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 1);
    List<RecipeIngredient> ingredientSpy = spy(new ArrayList<>(version.getIngredients()));
    version.setIngredients(ingredientSpy);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 1))
        .thenReturn(Optional.of(version));

    RecipeVersionDto dto = service().getVersionByNumber(recipeId, branchId, 1);

    assertThat(dto).isNotNull();
    assertThat(dto.versionNumber()).isEqualTo(1);
    assertThat(dto.ingredients()).hasSize(3);
    verify(ingredientSpy, atLeastOnce()).size();
  }

  @Test
  void getVersionByNumber_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getVersionByNumber(recipeId, UUID.randomUUID(), 1))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void getVersionByNumber_missingVersion_throwsVersionNotFound() {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 9))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getVersionByNumber(recipeId, branchId, 9))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  // ---------------- substitutions: reads ----------------

  @Test
  void getActiveSubstitutions_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getActiveSubstitutions(recipeId))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void getActiveSubstitutions_returnsAcceptedRows() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeSubstitution sub =
        beefToSoySub(
            UUID.randomUUID(), recipeId, UUID.randomUUID(), null, SubstitutionState.ACCEPTED);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(substitutionRepository.findAllByRecipeIdAndStateOrderByLastAppliedAtDesc(
            recipeId, SubstitutionState.ACCEPTED))
        .thenReturn(List.of(sub));

    List<RecipeSubstitutionDto> result = service().getActiveSubstitutions(recipeId);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).substitute().ingredientMappingKey()).isEqualTo("soy.crumble");
  }

  @Test
  void getSubstitutionsForVersion_singleArg_defaultsToAccepted() {
    UUID versionId = UUID.randomUUID();
    RecipeSubstitution sub =
        beefToSoySub(
            UUID.randomUUID(), UUID.randomUUID(), versionId, null, SubstitutionState.ACCEPTED);
    when(substitutionRepository.findAllByVersionIdAndStateOrderByLastAppliedAtDesc(
            versionId, SubstitutionState.ACCEPTED))
        .thenReturn(List.of(sub));

    assertThat(service().getSubstitutionsForVersion(versionId)).hasSize(1);
  }

  @Test
  void getSubstitution_present_mapsRow() {
    UUID subId = UUID.randomUUID();
    RecipeSubstitution sub =
        beefToSoySub(subId, UUID.randomUUID(), UUID.randomUUID(), null, SubstitutionState.PROPOSED);
    when(substitutionRepository.findById(subId)).thenReturn(Optional.of(sub));

    Optional<RecipeSubstitutionDto> result = service().getSubstitution(subId);

    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(subId);
  }

  @Test
  void getVersionWithSubstitutions_overlaysAcceptedSubs() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion version = versionWithDefaultBody(versionId, recipe, null, 1);
    List<RecipeIngredient> ingredientSpy = spy(new ArrayList<>(version.getIngredients()));
    version.setIngredients(ingredientSpy);
    RecipeSubstitution sub =
        beefToSoySub(UUID.randomUUID(), recipeId, versionId, null, SubstitutionState.ACCEPTED);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(substitutionRepository.findAllByVersionIdAndStateOrderByLastAppliedAtDesc(
            versionId, SubstitutionState.ACCEPTED))
        .thenReturn(List.of(sub));

    RecipeVersionDto dto = service().getVersionWithSubstitutions(recipeId, versionId);

    assertThat(dto).isNotNull();
    assertThat(dto.ingredients())
        .extracting(i -> i.ingredientMappingKey())
        .contains("soy.crumble")
        .doesNotContain("beef.mince");
    assertThat(dto.appliedSubstitutionIds()).containsExactly(sub.getId());
    verify(ingredientSpy, atLeastOnce()).size();
  }

  @Test
  void getVersionWithSubstitutions_versionOfOtherRecipe_throwsVersionNotFound() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    Recipe other = ownedRecipe(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion foreignVersion = versionWithDefaultBody(versionId, other, null, 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(foreignVersion));

    assertThatThrownBy(() -> service().getVersionWithSubstitutions(recipeId, versionId))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void getVersionWithSubstitutions_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getVersionWithSubstitutions(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  // ---------------- saveAdaptedSubstitution ----------------

  @Test
  void saveAdaptedSubstitution_persistsAcceptedRow_withPipelineActor_andBothEvents() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID adapterTraceId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion version = versionWithDefaultBody(versionId, recipe, branch, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(ingredientRepository.findMappingKeysByVersionId(versionId))
        .thenReturn(List.of("beef.mince"));
    when(substitutionRepository.saveAndFlush(any(RecipeSubstitution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RecipeSubstitutionDto dto =
        service()
            .saveAdaptedSubstitution(
                RecipeTestData.defaultSaveAdaptedSubstitutionCommand(
                    recipeId, versionId, adapterTraceId));

    assertThat(dto).isNotNull();

    ArgumentCaptor<RecipeSubstitution> subCaptor =
        ArgumentCaptor.forClass(RecipeSubstitution.class);
    verify(substitutionRepository).saveAndFlush(subCaptor.capture());
    RecipeSubstitution saved = subCaptor.getValue();
    assertThat(saved.getState()).isEqualTo(SubstitutionState.ACCEPTED);
    assertThat(saved.getBranchId()).isEqualTo(branchId);
    assertThat(saved.getCreatedByActor()).isEqualTo("pipeline:" + adapterTraceId);
    assertThat(saved.getMethodOverlay()).isNull();

    List<Object> events = publishedEvents(2);
    assertThat(onlyEvent(events, RecipeSubstitutionCreatedEvent.class).recipeId())
        .isEqualTo(recipeId);
    RecipeAdaptedEvent adapted = onlyEvent(events, RecipeAdaptedEvent.class);
    assertThat(adapted.outcomeType()).isEqualTo(AdaptationOutcomeType.SUBSTITUTION);
    assertThat(adapted.adapterTraceId()).isEqualTo(adapterTraceId);
  }

  @Test
  void saveAdaptedSubstitution_nullTraceId_usesUnknownActor_andCarriesOverlay() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion version = versionWithDefaultBody(versionId, recipe, null, 1);

    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(ingredientRepository.findMappingKeysByVersionId(versionId))
        .thenReturn(List.of("beef.mince"));
    when(substitutionRepository.saveAndFlush(any(RecipeSubstitution.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SaveAdaptedSubstitutionCommand cmd =
        new SaveAdaptedSubstitutionCommand(
            recipeId,
            versionId,
            new SubstitutionItemRequest("beef.mince", new BigDecimal("500.000"), "g"),
            new SubstitutionItemRequest("soy.crumble", new BigDecimal("400.000"), "g"),
            SubstitutionReason.DIETARY_TEMP,
            null,
            List.of(new MethodOverlayLineRequest(2, "Simmer the crumble instead.")),
            null,
            true,
            null);

    service().saveAdaptedSubstitution(cmd);

    ArgumentCaptor<RecipeSubstitution> subCaptor =
        ArgumentCaptor.forClass(RecipeSubstitution.class);
    verify(substitutionRepository).saveAndFlush(subCaptor.capture());
    assertThat(subCaptor.getValue().getCreatedByActor()).isEqualTo("pipeline:unknown");
    assertThat(subCaptor.getValue().getMethodOverlay()).hasSize(1);
    assertThat(subCaptor.getValue().getMethodOverlay().get(0).instruction())
        .isEqualTo("Simmer the crumble instead.");
  }

  @Test
  void saveAdaptedSubstitution_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .saveAdaptedSubstitution(
                        RecipeTestData.defaultSaveAdaptedSubstitutionCommand(
                            recipeId, UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void saveAdaptedSubstitution_versionOfOtherRecipe_throwsVersionNotFound() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    Recipe other = ownedRecipe(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion foreignVersion = versionWithDefaultBody(versionId, other, null, 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(foreignVersion));

    assertThatThrownBy(
            () ->
                service()
                    .saveAdaptedSubstitution(
                        RecipeTestData.defaultSaveAdaptedSubstitutionCommand(
                            recipeId, versionId, UUID.randomUUID())))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void saveAdaptedSubstitution_originalNotInVersion_throws() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion version = versionWithDefaultBody(versionId, recipe, null, 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.of(recipe));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(ingredientRepository.findMappingKeysByVersionId(versionId))
        .thenReturn(List.of("spaghetti.dry"));

    assertThatThrownBy(
            () ->
                service()
                    .saveAdaptedSubstitution(
                        RecipeTestData.defaultSaveAdaptedSubstitutionCommand(
                            recipeId, versionId, UUID.randomUUID())))
        .isInstanceOf(SubstitutionOriginalNotInVersionException.class);
  }

  @Test
  void saveAdaptedVersion_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findByIdForUpdate(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .saveAdaptedVersion(
                        RecipeTestData.defaultSaveAdaptedVersionCommand(
                            recipeId, UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void saveAdaptedBranch_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .saveAdaptedBranch(
                        RecipeTestData.defaultSaveAdaptedBranchCommand(
                            recipeId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  // ---------------- promote / demote ----------------

  @Test
  void promoteToUserCatalogue_flipsCatalogueAndOwner_returnsHydratedDto() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = systemRecipe(recipeId);
    recipe.setCurrentBranchId(branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion version = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 1);
    List<RecipeIngredient> ingredientSpy = spy(new ArrayList<>(version.getIngredients()));
    version.setIngredients(ingredientSpy);

    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.findAllByRecipeId(recipeId)).thenReturn(List.of(branch));
    when(versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, 1))
        .thenReturn(Optional.of(version));

    RecipeDto dto = service().promoteToUserCatalogue(recipeId, userId);

    assertThat(dto).isNotNull();
    assertThat(dto.currentVersionBody()).isNotNull();
    assertThat(dto.branches()).hasSize(1);

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getCatalogue()).isEqualTo(Catalogue.USER);
    assertThat(recipeCaptor.getValue().getUserId()).isEqualTo(userId);

    List<Object> events = publishedEvents(1);
    RecipePromotedEvent event = onlyEvent(events, RecipePromotedEvent.class);
    assertThat(event.fromCatalogue()).isEqualTo(Catalogue.SYSTEM);
    assertThat(event.toCatalogue()).isEqualTo(Catalogue.USER);
    assertThat(event.userId()).isEqualTo(userId);

    verify(ingredientSpy, atLeastOnce()).size();
  }

  @Test
  void promoteToUserCatalogue_withoutCurrentBranch_returnsDtoWithoutBody() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = systemRecipe(recipeId);
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.findAllByRecipeId(recipeId)).thenReturn(List.of());

    RecipeDto dto = service().promoteToUserCatalogue(recipeId, UUID.randomUUID());

    assertThat(dto).isNotNull();
    assertThat(dto.currentVersionBody()).isNull();
    verify(versionRepository, never())
        .findFirstByRecipeIdAndBranchIdAndVersionNumber(any(), any(), anyInt());
  }

  @Test
  void promoteToUserCatalogue_missing_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().promoteToUserCatalogue(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void promoteToUserCatalogue_alreadyUserCatalogue_throwsViolation() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().promoteToUserCatalogue(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeCatalogueViolationException.class);
  }

  @Test
  void promoteToUserCatalogue_deleted_throwsViolation() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = systemRecipe(recipeId);
    recipe.setDeletedAt(fixedInstant);
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().promoteToUserCatalogue(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeCatalogueViolationException.class);
  }

  @Test
  void promoteToUserCatalogue_archived_throwsViolation() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = systemRecipe(recipeId);
    recipe.setArchivedAt(fixedInstant);
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().promoteToUserCatalogue(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeCatalogueViolationException.class);
  }

  @Test
  void demoteToSystemCatalogue_flipsCatalogue_andPublishesArchivedEvent() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    service().demoteToSystemCatalogue(recipeId, userId);

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getCatalogue()).isEqualTo(Catalogue.SYSTEM);
    assertThat(recipeCaptor.getValue().getUserId()).isEqualTo(userId);

    List<Object> events = publishedEvents(1);
    RecipeArchivedEvent event = onlyEvent(events, RecipeArchivedEvent.class);
    assertThat(event.cause()).isEqualTo(ArchiveCause.USER_DEMOTION);
    assertThat(event.recipeId()).isEqualTo(recipeId);
  }

  @Test
  void demoteToSystemCatalogue_notOwner_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().demoteToSystemCatalogue(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void demoteToSystemCatalogue_alreadySystem_throwsViolation() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    recipe.setCatalogue(Catalogue.SYSTEM);
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().demoteToSystemCatalogue(recipeId, userId))
        .isInstanceOf(RecipeCatalogueViolationException.class);
  }

  // ---------------- archive / unarchive ----------------

  @Test
  void archive_stampsArchivedAt_andPublishesManualAdminEvent() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    service().archive(recipeId, userId);

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getArchivedAt()).isEqualTo(fixedInstant);

    List<Object> events = publishedEvents(1);
    RecipeArchivedEvent event = onlyEvent(events, RecipeArchivedEvent.class);
    assertThat(event.cause()).isEqualTo(ArchiveCause.MANUAL_ADMIN);
    assertThat(event.recipeId()).isEqualTo(recipeId);
  }

  @Test
  void archive_userRecipeNotOwned_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().archive(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeNotFoundException.class);
    verify(recipeRepository, never()).saveAndFlush(any());
  }

  @Test
  void archive_systemRecipe_isOpenToAnyCaller() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = systemRecipe(recipeId);
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    service().archive(recipeId, UUID.randomUUID());

    assertThat(recipe.getArchivedAt()).isEqualTo(fixedInstant);
  }

  @Test
  void archive_deletedRecipe_throwsViolation() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    recipe.setDeletedAt(fixedInstant);
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().archive(recipeId, userId))
        .isInstanceOf(RecipeCatalogueViolationException.class);
  }

  @Test
  void archive_alreadyArchived_isSilentNoop() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    recipe.setArchivedAt(fixedInstant.minusSeconds(60));
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    service().archive(recipeId, userId);

    verify(recipeRepository, never()).saveAndFlush(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void archive_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().archive(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void unarchive_clearsArchivedAt_withoutEvent() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    recipe.setArchivedAt(fixedInstant.minusSeconds(60));
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    service().unarchive(recipeId, userId);

    ArgumentCaptor<Recipe> recipeCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeCaptor.capture());
    assertThat(recipeCaptor.getValue().getArchivedAt()).isNull();
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void unarchive_notArchived_isSilentNoop() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, UUID.randomUUID());
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    service().unarchive(recipeId, userId);

    verify(recipeRepository, never()).saveAndFlush(any());
  }

  @Test
  void unarchive_userRecipeNotOwned_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, UUID.randomUUID(), UUID.randomUUID());
    recipe.setArchivedAt(fixedInstant);
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().unarchive(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void unarchive_missingRecipe_throwsNotFound() {
    UUID recipeId = UUID.randomUUID();
    when(recipeRepository.findById(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().unarchive(recipeId, UUID.randomUUID()))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  // ---------------- bulk archive by import job ----------------

  @Test
  void archiveByImportJobId_archivesFreshSystemRows_skipsIneligibleOnes() {
    UUID jobId = UUID.randomUUID();
    UUID missingId = UUID.randomUUID();
    UUID deletedId = UUID.randomUUID();
    UUID promotedId = UUID.randomUUID();
    UUID freshId = UUID.randomUUID();
    UUID alreadyArchivedId = UUID.randomUUID();

    Recipe deleted = systemRecipe(deletedId);
    deleted.setDeletedAt(fixedInstant.minusSeconds(60));
    Recipe promoted = systemRecipe(promotedId);
    promoted.setCatalogue(Catalogue.USER);
    Recipe fresh = systemRecipe(freshId);
    Recipe alreadyArchived = systemRecipe(alreadyArchivedId);
    alreadyArchived.setArchivedAt(fixedInstant.minusSeconds(60));

    when(importRepository.findRecipeIdsByJobIdAndSourceType(jobId, ImportSource.AI_GENERATED))
        .thenReturn(List.of(missingId, deletedId, promotedId, freshId, alreadyArchivedId));
    when(recipeRepository.findById(missingId)).thenReturn(Optional.empty());
    when(recipeRepository.findById(deletedId)).thenReturn(Optional.of(deleted));
    when(recipeRepository.findById(promotedId)).thenReturn(Optional.of(promoted));
    when(recipeRepository.findById(freshId)).thenReturn(Optional.of(fresh));
    when(recipeRepository.findById(alreadyArchivedId)).thenReturn(Optional.of(alreadyArchived));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    ImportJobArchiveResult result = service().archiveByImportJobId(jobId, UUID.randomUUID());

    assertThat(result).isNotNull();
    assertThat(result.matchedRecipeIds())
        .containsExactly(missingId, deletedId, promotedId, freshId, alreadyArchivedId);
    assertThat(result.changedRecipeIds()).containsExactly(freshId);
    assertThat(result.skippedRecipeIds()).containsExactly(missingId, deletedId, promotedId);
    assertThat(fresh.getArchivedAt()).isEqualTo(fixedInstant);

    List<Object> events = publishedEvents(1);
    RecipeArchivedEvent event = onlyEvent(events, RecipeArchivedEvent.class);
    assertThat(event.recipeId()).isEqualTo(freshId);
    assertThat(event.cause()).isEqualTo(ArchiveCause.MANUAL_ADMIN);
    verify(recipeRepository, times(1)).saveAndFlush(any(Recipe.class));
  }

  @Test
  void unarchiveByImportJobId_restoresArchivedRows_withoutEvents() {
    UUID jobId = UUID.randomUUID();
    UUID archivedId = UUID.randomUUID();
    UUID freshId = UUID.randomUUID();
    Recipe archived = systemRecipe(archivedId);
    archived.setArchivedAt(fixedInstant.minusSeconds(60));
    Recipe fresh = systemRecipe(freshId);

    when(importRepository.findRecipeIdsByJobIdAndSourceType(jobId, ImportSource.AI_GENERATED))
        .thenReturn(List.of(archivedId, freshId));
    when(recipeRepository.findById(archivedId)).thenReturn(Optional.of(archived));
    when(recipeRepository.findById(freshId)).thenReturn(Optional.of(fresh));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    ImportJobArchiveResult result = service().unarchiveByImportJobId(jobId, UUID.randomUUID());

    assertThat(result).isNotNull();
    assertThat(result.changedRecipeIds()).containsExactly(archivedId);
    assertThat(result.skippedRecipeIds()).isEmpty();
    assertThat(archived.getArchivedAt()).isNull();
    verify(eventPublisher, never()).publishEvent(any());
    verify(recipeRepository, times(1)).saveAndFlush(any(Recipe.class));
  }

  // ---------------- searchLibrary ----------------

  @Test
  void searchLibrary_hydratesBodyBranchesAndRatingAggregate() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(recipeId, userId, branchId);
    RecipeBranch branch = branchOf(recipe, branchId, "main", 1);
    RecipeVersion version = versionWithDefaultBody(UUID.randomUUID(), recipe, branch, 1);
    Pageable pageable = PageRequest.of(0, 20);

    when(recipeRepository.searchLibrary(
            any(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any(),
            any(),
            anyCollection(),
            any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(recipe), pageable, 1));
    when(branchRepository.findAllByRecipeIdIn(List.of(recipeId))).thenReturn(List.of(branch));
    when(versionRepository.findCurrentVersionsForRecipes(List.of(recipeId)))
        .thenReturn(List.of(version));
    when(ratingRepository.aggregateTasteForRecipes(List.of(recipeId)))
        .thenReturn(List.<Object[]>of(new Object[] {recipeId, 4.5d, 3L}));

    RecipeSearchCriteriaDto criteria =
        new RecipeSearchCriteriaDto(null, "pa", null, null, null, false);
    Page<RecipeDto> page = service().searchLibrary(userId, criteria, pageable);

    assertThat(page.getContent()).hasSize(1);
    RecipeDto dto = page.getContent().get(0);
    assertThat(dto.currentVersionBody()).isNotNull();
    assertThat(dto.branches()).hasSize(1);
    assertThat(dto.avgTaste()).isEqualTo(4.5d);
    assertThat(dto.ratingCount()).isEqualTo(3L);

    verify(versionRepository).findWithIngredientsByIdIn(List.of(version.getId()));
    verify(versionRepository).findWithMethodStepsByIdIn(List.of(version.getId()));
    verify(recipeRepository)
        .searchLibrary(
            eq(userId),
            eq(true),
            eq(true),
            eq(false),
            eq("%pa%"),
            any(),
            any(),
            anyCollection(),
            any(Pageable.class));
  }

  // ---------------- SPI odds and ends ----------------

  @Test
  void storeEmbedding_emptyVector_writesEmptyLiteral() {
    UUID versionId = UUID.randomUUID();
    RecipeVersion version = bareVersion(versionId, null, null, 1);
    when(versionRepository.updateEmbedding(eq(versionId), any(String.class), eq("m"), any()))
        .thenReturn(1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    service().storeEmbedding(versionId, new float[0], "m");

    ArgumentCaptor<String> pgCaptor = ArgumentCaptor.forClass(String.class);
    verify(versionRepository).updateEmbedding(eq(versionId), pgCaptor.capture(), eq("m"), any());
    assertThat(pgCaptor.getValue()).isEqualTo("[]");
  }

  @Test
  void storeEmbedding_versionWithoutRecipe_publishesEventWithNullRecipeId() {
    UUID versionId = UUID.randomUUID();
    RecipeVersion version = bareVersion(versionId, null, null, 1);
    when(versionRepository.updateEmbedding(eq(versionId), any(String.class), eq("m"), any()))
        .thenReturn(1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

    service().storeEmbedding(versionId, new float[] {1.0f}, "m");

    ArgumentCaptor<Object> evCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(evCaptor.capture());
    com.example.mealprep.recipe.event.RecipeEvolvedEvent event =
        (com.example.mealprep.recipe.event.RecipeEvolvedEvent) evCaptor.getValue();
    assertThat(event.recipeId()).isNull();
    assertThat(event.versionId()).isEqualTo(versionId);
  }

  @Test
  void updateNutritionStatus_nullBody_isAcceptedAndPublishes() {
    UUID versionId = UUID.randomUUID();
    Recipe recipe = ownedRecipe(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion version = bareVersion(versionId, recipe, null, 1);
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    service().updateNutritionStatus(versionId, NutritionStatus.CALCULATED, null);

    assertThat(recipe.getNutritionStatus()).isEqualTo(NutritionStatus.CALCULATED);
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(published.capture());
    com.example.mealprep.recipe.event.RecipeEvolvedEvent event =
        (com.example.mealprep.recipe.event.RecipeEvolvedEvent) published.getValue();
    assertThat(event.versionId()).isEqualTo(versionId);
  }

  @Test
  void updateCharacterFingerprint_missingVersion_throwsVersionNotFound() {
    UUID versionId = UUID.randomUUID();
    when(versionRepository.findById(versionId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .updateCharacterFingerprint(versionId, RecipeTestData.defaultFingerprint()))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void updateBranchDivergence_missingBranch_throwsBranchNotFound() {
    UUID branchId = UUID.randomUUID();
    when(branchRepository.findById(branchId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().updateBranchDivergence(branchId, new BigDecimal("0.500")))
        .isInstanceOf(RecipeBranchNotFoundException.class);
  }

  @Test
  void findUserRecipeIngredientKeys_nullUser_returnsEmptyWithoutQuerying() {
    assertThat(service().findUserRecipeIngredientKeys(null)).isEmpty();
    verify(recipeRepository, never()).findCurrentVersionIngredientKeysForUser(any());
  }

  @Test
  void findUserRecipeIngredientKeys_groupsRowsPerRecipe() {
    UUID userId = UUID.randomUUID();
    UUID recipeA = UUID.randomUUID();
    UUID recipeB = UUID.randomUUID();
    when(recipeRepository.findCurrentVersionIngredientKeysForUser(userId))
        .thenReturn(
            List.of(
                new Object[] {recipeA, "beef.mince"},
                new Object[] {recipeA, "tomato.passata"},
                new Object[] {recipeB, "tofu.firm"}));

    Map<UUID, List<String>> result = service().findUserRecipeIngredientKeys(userId);

    assertThat(result).hasSize(2);
    assertThat(result.get(recipeA)).containsExactly("beef.mince", "tomato.passata");
    assertThat(result.get(recipeB)).containsExactly("tofu.firm");
  }

  @Test
  void findUserRecipeNutrition_nullUser_returnsEmptyWithoutQuerying() {
    assertThat(service().findUserRecipeNutrition(null)).isEmpty();
    verify(recipeRepository, never()).findCurrentVersionNutritionForUser(any());
  }

  @Test
  void findUserRecipeNutrition_mapsRowsVerbatim() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    JsonNode nutrition = JsonNodeFactory.instance.objectNode().put("kcal", 512);
    when(recipeRepository.findCurrentVersionNutritionForUser(userId))
        .thenReturn(List.<Object[]>of(new Object[] {recipeId, nutrition}));

    Map<UUID, JsonNode> result = service().findUserRecipeNutrition(userId);

    assertThat(result).hasSize(1);
    assertThat(result.get(recipeId).get("kcal").asInt()).isEqualTo(512);
  }

  @Test
  void rejectSubstitution_missingSubstitution_throwsNotFound() {
    UUID subId = UUID.randomUUID();
    when(substitutionRepository.findById(subId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().rejectSubstitution(subId, UUID.randomUUID(), 0, null))
        .isInstanceOf(RecipeSubstitutionNotFoundException.class);
  }

  @Test
  void rejectSubstitution_recipeGone_throwsSubstitutionNotFound() {
    UUID subId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    RecipeSubstitution sub =
        beefToSoySub(subId, recipeId, UUID.randomUUID(), null, SubstitutionState.PROPOSED);
    when(substitutionRepository.findById(subId)).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().rejectSubstitution(subId, UUID.randomUUID(), 0, null))
        .isInstanceOf(RecipeSubstitutionNotFoundException.class);
  }
}
