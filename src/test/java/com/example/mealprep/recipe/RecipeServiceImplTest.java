package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.mapper.IngredientMapper;
import com.example.mealprep.recipe.api.mapper.MethodStepMapper;
import com.example.mealprep.recipe.api.mapper.ParsedRecipeToCreateRequestMapper;
import com.example.mealprep.recipe.api.mapper.RecipeBranchMapper;
import com.example.mealprep.recipe.api.mapper.RecipeDiffMapper;
import com.example.mealprep.recipe.api.mapper.RecipeImportMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMetadataMapper;
import com.example.mealprep.recipe.api.mapper.RecipeTagsMapper;
import com.example.mealprep.recipe.api.mapper.RecipeVersionMapper;
import com.example.mealprep.recipe.config.UrlFetcher;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.ImportSource;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeImport;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.repository.RecipeBranchRepository;
import com.example.mealprep.recipe.domain.repository.RecipeImportRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.internal.DivergenceScoreCalculator;
import com.example.mealprep.recipe.domain.service.internal.FingerprintDeriver;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser.ParsedRecipe;
import com.example.mealprep.recipe.domain.service.internal.RecipeServiceImpl;
import com.example.mealprep.recipe.domain.service.internal.VersionDiffer;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.example.mealprep.recipe.event.RecipeVersionCreatedEvent;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Unit test for {@link RecipeServiceImpl}. Repositories and the event publisher are mocked at the
 * module boundary; the real mappers are used because they are deterministic, no-I/O, and central to
 * behaviour.
 */
@ExtendWith(MockitoExtension.class)
class RecipeServiceImplTest {

  @Mock private RecipeRepository recipeRepository;
  @Mock private RecipeBranchRepository branchRepository;
  @Mock private RecipeVersionRepository versionRepository;
  @Mock private RecipeImportRepository importRepository;
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
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HtmlImportParser htmlImportParser = new HtmlImportParser(objectMapper);
  private final ParsedRecipeToCreateRequestMapper parserToCreateRequestMapper =
      new ParsedRecipeToCreateRequestMapper();
  private final VersionDiffer versionDiffer = new VersionDiffer(objectMapper);
  private final DivergenceScoreCalculator divergenceCalculator = new DivergenceScoreCalculator();
  private final FingerprintDeriver fingerprintDeriver = new FingerprintDeriver();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);

  private RecipeServiceImpl service() {
    return new RecipeServiceImpl(
        recipeRepository,
        branchRepository,
        versionRepository,
        importRepository,
        null,
        null,
        recipeMapper,
        versionMapper,
        branchMapper,
        importMapper,
        diffMapper,
        new com.example.mealprep.recipe.api.mapper.RecipeSubstitutionMapper(),
        urlFetcher,
        htmlImportParser,
        parserToCreateRequestMapper,
        versionDiffer,
        divergenceCalculator,
        fingerprintDeriver,
        new com.example.mealprep.recipe.domain.service.internal.SubstitutionOverlayApplier(),
        objectMapper,
        eventPublisher,
        fixedClock);
  }

  @Test
  void createRecipe_buildsRoot_branch_versionBody_andPublishesEvents() {
    UUID userId = UUID.randomUUID();

    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    RecipeDto dto = service().createRecipe(userId, RecipeTestData.defaultCreateRequest());

    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.name()).isEqualTo("Spaghetti Bolognese");
    assertThat(dto.currentVersion()).isEqualTo(1);
    assertThat(dto.currentBranchId()).isNotNull();
    assertThat(dto.currentVersionBody()).isNotNull();
    assertThat(dto.currentVersionBody().ingredients()).hasSize(3);
    assertThat(dto.currentVersionBody().methodSteps()).hasSize(3);
    assertThat(dto.currentVersionBody().embeddingStatus()).isEqualTo("pending");
    assertThat(dto.branches()).hasSize(1);
    assertThat(dto.branches().get(0).name()).isEqualTo("main");

    ArgumentCaptor<Recipe> recipeFlushCaptor = ArgumentCaptor.forClass(Recipe.class);
    verify(recipeRepository).saveAndFlush(recipeFlushCaptor.capture());
    assertThat(recipeFlushCaptor.getValue().getCurrentBranchId()).isNotNull();

    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues())
        .anyMatch(RecipeCreatedEvent.class::isInstance)
        .anyMatch(RecipeVersionCreatedEvent.class::isInstance);
  }

  @Test
  void createRecipe_withoutTags_insertsAllNullTagFields() {
    UUID userId = UUID.randomUUID();

    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    RecipeDto dto = service().createRecipe(userId, RecipeTestData.createRequestWithoutTags());

    assertThat(dto.currentVersionBody().tags()).isNotNull();
    assertThat(dto.currentVersionBody().tags().protein()).isNull();
    assertThat(dto.currentVersionBody().tags().cookingMethod()).isNull();
    assertThat(dto.currentVersionBody().tags().complexity()).isNull();
    assertThat(dto.currentVersionBody().tags().flavourProfile()).isEmpty();
    assertThat(dto.currentVersionBody().tags().dietaryFlags()).isEmpty();
  }

  @Test
  void createRecipe_normalisesIngredientMappingKeys_whenPersisting() {
    // core-03: a raw mixed-case / extra-whitespace mapping key on the create request must be
    // persisted in normalised form (lowercase, trimmed, internal whitespace collapsed).
    UUID userId = UUID.randomUUID();

    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateRecipeRequest request =
        new CreateRecipeRequest(
            "Chicken Dinner",
            "Test",
            List.of(
                new CreateIngredientRequest(
                    0,
                    "  Chicken   Breast ", // RAW
                    "Chicken breast",
                    new BigDecimal("500.000"),
                    "g",
                    null,
                    false)),
            RecipeTestData.defaultMethod(),
            RecipeTestData.defaultMetadata(),
            RecipeTestData.defaultTags());

    RecipeDto dto = service().createRecipe(userId, request);

    assertThat(dto.currentVersionBody().ingredients()).hasSize(1);
    assertThat(dto.currentVersionBody().ingredients().get(0).ingredientMappingKey())
        .isEqualTo("chicken breast");

    // Confirm against what was actually flushed to the version repository (not just the DTO).
    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getIngredients().get(0).getIngredientMappingKey())
        .isEqualTo("chicken breast");
  }

  // ---------------- saveImportedRecipe: discovery-1 ingredient_mapping_key population ----------

  private void stubImportPersistence() {
    when(importRepository.findByContentFingerprint(any())).thenReturn(Optional.empty());
    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private static com.example.mealprep.recipe.spi.ImportedRecipeData importedRecipeWith(
      List<com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedIngredient> ingredients) {
    return new com.example.mealprep.recipe.spi.ImportedRecipeData(
        "bbc_good_food",
        "https://example.test/r/1",
        "fingerprint-1",
        "Imported Recipe",
        "desc",
        ingredients,
        List.of(
            new com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedMethodStep(1, "Go.", 5)),
        null,
        null,
        "json_ld",
        new BigDecimal("0.85"),
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private RecipeVersion captureSavedImportVersion() {
    ArgumentCaptor<RecipeVersion> versionCaptor = ArgumentCaptor.forClass(RecipeVersion.class);
    verify(versionRepository).saveAndFlush(versionCaptor.capture());
    return versionCaptor.getValue();
  }

  @Test
  void saveImportedRecipe_carriesAndNormalisesSuppliedMappingKey() {
    // discovery-1: the carried key is normalised and persisted, NOT hardcoded null (the old bug).
    stubImportPersistence();
    var data =
        importedRecipeWith(
            List.of(
                new com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedIngredient(
                    0, "Sea Salt", "  Sea   Salt ", BigDecimal.ONE, "tsp", null, false)));

    service().saveImportedRecipe(data);

    RecipeVersion version = captureSavedImportVersion();
    assertThat(version.getIngredients()).hasSize(1);
    assertThat(version.getIngredients().get(0).getIngredientMappingKey()).isEqualTo("sea salt");
    assertThat(version.getIngredients().get(0).getDisplayName()).isEqualTo("Sea Salt");
  }

  @Test
  void saveImportedRecipe_nullCarriedKey_fallsBackToNormalisedDisplayName() {
    // The final NOT-NULL invariant guard: a null carried key falls back to normalise(displayName)
    // so the row can persist against the NOT-NULL ingredient_mapping_key column.
    stubImportPersistence();
    var data =
        importedRecipeWith(
            List.of(
                new com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedIngredient(
                    0, "  Chicken   Breast ", null, BigDecimal.ONE, "g", null, false)));

    service().saveImportedRecipe(data);

    RecipeVersion version = captureSavedImportVersion();
    assertThat(version.getIngredients()).hasSize(1);
    assertThat(version.getIngredients().get(0).getIngredientMappingKey())
        .isEqualTo("chicken breast");
  }

  @Test
  void saveImportedRecipe_blankCarriedKey_fallsBackToNormalisedDisplayName() {
    // A blank (whitespace-only) carried key is also treated as absent → displayName fallback.
    stubImportPersistence();
    var data =
        importedRecipeWith(
            List.of(
                new com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedIngredient(
                    0, "Olive Oil", "   ", BigDecimal.ONE, "tbsp", null, false)));

    service().saveImportedRecipe(data);

    RecipeVersion version = captureSavedImportVersion();
    assertThat(version.getIngredients().get(0).getIngredientMappingKey()).isEqualTo("olive oil");
  }

  @Test
  void saveImportedRecipe_blankDisplayNameAndNoKey_skipsLine_neverNullKey() {
    // Invariant: a line with neither a usable key nor a usable displayName is skipped, never
    // persisted with a null/blank key (which would violate the NOT-NULL column). The valid line
    // still persists with its normalised key.
    stubImportPersistence();
    var data =
        importedRecipeWith(
            List.of(
                new com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedIngredient(
                    0, "   ", null, BigDecimal.ONE, "g", null, false),
                new com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedIngredient(
                    1, "Flour", null, BigDecimal.TEN, "g", null, false)));

    service().saveImportedRecipe(data);

    RecipeVersion version = captureSavedImportVersion();
    assertThat(version.getIngredients()).hasSize(1);
    assertThat(version.getIngredients().get(0).getDisplayName()).isEqualTo("Flour");
    assertThat(version.getIngredients().get(0).getIngredientMappingKey()).isEqualTo("flour");
    assertThat(version.getIngredients())
        .allSatisfy(ing -> assertThat(ing.getIngredientMappingKey()).isNotBlank());
  }

  // ---------------- findPlannableCandidates (planner Stage-A read) ----------------

  @Test
  void findPlannableCandidates_nullUserId_returnsEmpty_withoutQuerying() {
    List<RecipeDto> result = service().findPlannableCandidates(null, 10);

    assertThat(result).isEmpty();
    verify(recipeRepository, org.mockito.Mockito.never())
        .findPlannableForUser(any(), any(org.springframework.data.domain.Pageable.class));
  }

  @Test
  void findPlannableCandidates_zeroLimit_returnsEmpty_withoutQuerying() {
    List<RecipeDto> result = service().findPlannableCandidates(UUID.randomUUID(), 0);

    assertThat(result).isEmpty();
    verify(recipeRepository, org.mockito.Mockito.never())
        .findPlannableForUser(any(), any(org.springframework.data.domain.Pageable.class));
  }

  @Test
  void findPlannableCandidates_negativeLimit_returnsEmpty_withoutQuerying() {
    List<RecipeDto> result = service().findPlannableCandidates(UUID.randomUUID(), -5);

    assertThat(result).isEmpty();
    verify(recipeRepository, org.mockito.Mockito.never())
        .findPlannableForUser(any(), any(org.springframework.data.domain.Pageable.class));
  }

  @Test
  void findPlannableCandidates_emptyCatalogue_returnsEmpty_butQueriesWithPageRequest() {
    UUID userId = UUID.randomUUID();
    when(recipeRepository.findPlannableForUser(
            org.mockito.ArgumentMatchers.eq(userId),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(List.of());

    List<RecipeDto> result = service().findPlannableCandidates(userId, 25);

    assertThat(result).isEmpty();
    ArgumentCaptor<org.springframework.data.domain.Pageable> pageCaptor =
        ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    verify(recipeRepository)
        .findPlannableForUser(org.mockito.ArgumentMatchers.eq(userId), pageCaptor.capture());
    assertThat(pageCaptor.getValue().getPageNumber()).isZero();
    assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(25);
  }

  @Test
  void findPlannableCandidates_hydratesEachRow_defensiveNullBranchYieldsNullBody() {
    UUID userId = UUID.randomUUID();
    // A recipe whose currentBranchId is null exercises hydrate()'s defensive branch (null body) and
    // proves the loop maps every returned row. Two rows -> two DTOs.
    Recipe r1 =
        Recipe.builder().id(UUID.randomUUID()).currentBranchId(null).currentVersion(1).build();
    Recipe r2 =
        Recipe.builder().id(UUID.randomUUID()).currentBranchId(null).currentVersion(1).build();
    when(recipeRepository.findPlannableForUser(
            org.mockito.ArgumentMatchers.eq(userId),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(List.of(r1, r2));
    when(branchRepository.findAllByRecipeId(any())).thenReturn(List.of());

    List<RecipeDto> result = service().findPlannableCandidates(userId, 10);

    assertThat(result).hasSize(2);
    assertThat(result).allSatisfy(dto -> assertThat(dto.currentVersionBody()).isNull());
    assertThat(result.stream().map(RecipeDto::id))
        .containsExactlyInAnyOrder(r1.getId(), r2.getId());
    // versionRepository is never consulted when currentBranchId is null (defensive short-circuit).
    verify(versionRepository, org.mockito.Mockito.never())
        .findFirstByRecipeIdAndBranchIdAndVersionNumber(
            any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void importFromUrl_orchestratesFetch_parse_persist_andWritesProvenance() {
    UUID userId = UUID.randomUUID();
    String sourceUrl = "https://example.com/recipe";
    String html = "<html/>";

    when(urlFetcher.fetch(sourceUrl)).thenReturn(html);

    HtmlImportParser stubParser =
        new HtmlImportParser(objectMapper) {
          @Override
          public ParsedRecipe parse(String h, String u) {
            return new ParsedRecipe(
                "Imported Pasta",
                "Quick weeknight bowl.",
                List.of("200g spaghetti", "1 jar passata"),
                List.of("Boil pasta.", "Heat sauce."),
                10,
                15,
                25,
                2,
                "Italian",
                "json_ld",
                JsonNodeFactory.instance.objectNode());
          }
        };

    RecipeServiceImpl impl =
        new RecipeServiceImpl(
            recipeRepository,
            branchRepository,
            versionRepository,
            importRepository,
            null,
            null,
            recipeMapper,
            versionMapper,
            branchMapper,
            importMapper,
            diffMapper,
            new com.example.mealprep.recipe.api.mapper.RecipeSubstitutionMapper(),
            urlFetcher,
            stubParser,
            parserToCreateRequestMapper,
            versionDiffer,
            divergenceCalculator,
            fingerprintDeriver,
            new com.example.mealprep.recipe.domain.service.internal.SubstitutionOverlayApplier(),
            objectMapper,
            eventPublisher,
            fixedClock);

    when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(branchRepository.save(any(RecipeBranch.class))).thenAnswer(inv -> inv.getArgument(0));
    when(versionRepository.saveAndFlush(any(RecipeVersion.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
    when(importRepository.save(any(RecipeImport.class))).thenAnswer(inv -> inv.getArgument(0));
    // After importFromUrl, the impl re-hydrates via getById; stub it returning empty so it falls
    // back to the freshly-built DTO.
    when(recipeRepository.findByIdAndDeletedAtIsNull(any(UUID.class))).thenReturn(Optional.empty());

    RecipeDto dto = impl.importFromUrl(userId, new ImportRecipeFromUrlRequest(sourceUrl, null));

    assertThat(dto).isNotNull();
    assertThat(dto.name()).isEqualTo("Imported Pasta");
    assertThat(dto.dataQuality()).isEqualTo(DataQuality.IMPORTED);
    assertThat(dto.currentVersionBody().trigger()).isEqualTo(VersionTrigger.IMPORT);

    ArgumentCaptor<RecipeImport> provCaptor = ArgumentCaptor.forClass(RecipeImport.class);
    verify(importRepository).save(provCaptor.capture());
    RecipeImport saved = provCaptor.getValue();
    assertThat(saved.getSourceType()).isEqualTo(ImportSource.URL);
    assertThat(saved.getSourceUrl()).isEqualTo(sourceUrl);
    assertThat(saved.getExtractionMethod()).isEqualTo("json_ld");
    assertThat(saved.getImportedByUserId()).isEqualTo(userId);
  }
}
