package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
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
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.repository.RecipeBranchRepository;
import com.example.mealprep.recipe.domain.repository.RecipeImportRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.internal.HtmlImportParser;
import com.example.mealprep.recipe.domain.service.internal.RecipeServiceImpl;
import com.example.mealprep.recipe.domain.service.internal.VersionDiffer;
import com.example.mealprep.recipe.exception.RecipeCatalogueViolationException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

/** Unit-level coverage of the manual-edit guards on {@link RecipeServiceImpl}. */
@ExtendWith(MockitoExtension.class)
class RecipeManualEditServiceTest {

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
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);

  private RecipeServiceImpl service() {
    return new RecipeServiceImpl(
        recipeRepository,
        branchRepository,
        versionRepository,
        importRepository,
        recipeMapper,
        versionMapper,
        branchMapper,
        importMapper,
        diffMapper,
        urlFetcher,
        htmlImportParser,
        parserToCreateRequestMapper,
        versionDiffer,
        eventPublisher,
        fixedClock);
  }

  private Recipe userRecipe(UUID userId, long optimistic) {
    return Recipe.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .catalogue(Catalogue.USER)
        .name("R")
        .currentVersion(1)
        .currentBranchId(UUID.randomUUID())
        .dataQuality(DataQuality.USER_VERIFIED)
        .nutritionStatus(NutritionStatus.PENDING)
        .optimisticVersion(optimistic)
        .build();
  }

  @Test
  void manualEdit_404_whenRecipeMissing() {
    UUID id = UUID.randomUUID();
    when(recipeRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());
    UpdateRecipeManualEditRequest req = RecipeTestData.defaultManualEditRequest(0);
    assertThatThrownBy(() -> service().manualEdit(id, req, UUID.randomUUID()))
        .isInstanceOf(RecipeNotFoundException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void manualEdit_404_whenOwnedByDifferentUser() {
    UUID userId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    Recipe recipe = userRecipe(userId, 1);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipe.getId()))
        .thenReturn(Optional.of(recipe));
    UpdateRecipeManualEditRequest req = RecipeTestData.defaultManualEditRequest(1);
    assertThatThrownBy(() -> service().manualEdit(recipe.getId(), req, otherUserId))
        .isInstanceOf(RecipeNotFoundException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void manualEdit_422_onSystemCatalogue() {
    UUID userId = UUID.randomUUID();
    Recipe recipe = userRecipe(userId, 1);
    recipe.setCatalogue(Catalogue.SYSTEM);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipe.getId()))
        .thenReturn(Optional.of(recipe));
    UpdateRecipeManualEditRequest req = RecipeTestData.defaultManualEditRequest(1);
    assertThatThrownBy(() -> service().manualEdit(recipe.getId(), req, userId))
        .isInstanceOf(RecipeCatalogueViolationException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void manualEdit_409_onStaleOptimisticVersion() {
    UUID userId = UUID.randomUUID();
    Recipe recipe = userRecipe(userId, 5);
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipe.getId()))
        .thenReturn(Optional.of(recipe));
    UpdateRecipeManualEditRequest req = RecipeTestData.defaultManualEditRequest(99);
    assertThatThrownBy(() -> service().manualEdit(recipe.getId(), req, userId))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void manualEditRequest_carries_changeReason_and_optimisticVersion() {
    UpdateRecipeManualEditRequest req = RecipeTestData.defaultManualEditRequest(7);
    assertThat(req.changeReason()).isNotBlank();
    assertThat(req.expectedOptimisticVersion()).isEqualTo(7);
    // sanity — the helper actually mutates one method-step duration so the diff is non-empty.
    assertThat(req.method().get(1).durationMinutes()).isEqualTo(35);
    // Avoid unused-import warnings.
    assertThat(BigDecimal.ZERO).isNotNull();
    assertThat(RecipeBranch.builder()).isNotNull();
  }
}
