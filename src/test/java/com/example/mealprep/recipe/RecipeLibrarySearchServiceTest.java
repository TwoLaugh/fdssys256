package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeSearchCriteriaDto;
import com.example.mealprep.recipe.api.mapper.IngredientMapper;
import com.example.mealprep.recipe.api.mapper.MethodStepMapper;
import com.example.mealprep.recipe.api.mapper.RecipeBranchMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMetadataMapper;
import com.example.mealprep.recipe.api.mapper.RecipeTagsMapper;
import com.example.mealprep.recipe.api.mapper.RecipeVersionMapper;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.repository.RecipeBranchRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRatingRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.internal.RecipeServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit test for {@link RecipeServiceImpl#searchLibrary} — the criteria-to-query translation
 * (catalogue booleans, LIKE-pattern escaping, quality-floor expansion) and the batched rating
 * aggregate merge. Repositories are mocked at the module boundary; real mappers (the
 * RecipeServiceImplTest convention).
 */
@ExtendWith(MockitoExtension.class)
class RecipeLibrarySearchServiceTest {

  @Mock private RecipeRepository recipeRepository;
  @Mock private RecipeBranchRepository branchRepository;
  @Mock private RecipeVersionRepository versionRepository;
  @Mock private RecipeRatingRepository ratingRepository;

  @Captor private ArgumentCaptor<Collection<DataQuality>> qualitiesCaptor;

  private final RecipeVersionMapper versionMapper =
      new RecipeVersionMapper(
          new IngredientMapper(),
          new MethodStepMapper(),
          new RecipeMetadataMapper(),
          new RecipeTagsMapper());
  private final RecipeMapper recipeMapper = new RecipeMapper();
  private final RecipeBranchMapper branchMapper = new RecipeBranchMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-06-12T10:00:00Z"), ZoneOffset.UTC);

  private RecipeServiceImpl service() {
    return new RecipeServiceImpl(
        recipeRepository,
        branchRepository,
        versionRepository,
        null,
        null,
        null,
        ratingRepository,
        recipeMapper,
        versionMapper,
        branchMapper,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        fixedClock);
  }

  private Recipe recipe(UUID id, UUID userId) {
    Recipe r =
        Recipe.builder()
            .id(id)
            .userId(userId)
            .catalogue(Catalogue.USER)
            .name("Recipe " + id)
            .currentVersion(1)
            .dataQuality(DataQuality.USER_VERIFIED)
            .nutritionStatus(NutritionStatus.PENDING)
            .build();
    return r;
  }

  private void stubSearchReturning(Recipe... recipes) {
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
        .thenAnswer(
            inv ->
                new PageImpl<>(
                    List.of(recipes), inv.getArgument(8, Pageable.class), recipes.length));
    if (recipes.length > 0) {
      when(branchRepository.findAllByRecipeIdIn(anyCollection())).thenReturn(List.of());
      when(versionRepository.findCurrentVersionsForRecipes(anyCollection())).thenReturn(List.of());
      when(ratingRepository.aggregateTasteForRecipes(anyCollection())).thenReturn(List.of());
    }
  }

  @Test
  void catalogueAbsent_meansBothUserAndSystem() {
    UUID userId = UUID.randomUUID();
    stubSearchReturning();
    service()
        .searchLibrary(
            userId,
            new RecipeSearchCriteriaDto(null, null, null, null, null, false),
            PageRequest.of(0, 20));
    verify(recipeRepository)
        .searchLibrary(
            eq(userId),
            eq(true),
            eq(true),
            eq(false),
            eq(null),
            eq(null),
            eq(null),
            anyCollection(),
            any(Pageable.class));
  }

  @Test
  void catalogueUser_restrictsToUserOnly_andSystemToSystemOnly() {
    UUID userId = UUID.randomUUID();
    stubSearchReturning();
    service()
        .searchLibrary(
            userId,
            new RecipeSearchCriteriaDto(Catalogue.USER, null, null, null, null, false),
            PageRequest.of(0, 20));
    verify(recipeRepository)
        .searchLibrary(
            eq(userId),
            eq(true),
            eq(false),
            eq(false),
            eq(null),
            eq(null),
            eq(null),
            anyCollection(),
            any(Pageable.class));

    stubSearchReturning();
    service()
        .searchLibrary(
            userId,
            new RecipeSearchCriteriaDto(Catalogue.SYSTEM, null, null, null, null, true),
            PageRequest.of(0, 20));
    verify(recipeRepository)
        .searchLibrary(
            eq(userId),
            eq(false),
            eq(true),
            eq(true),
            eq(null),
            eq(null),
            eq(null),
            anyCollection(),
            any(Pageable.class));
  }

  @Test
  void namePattern_isLowercased_wrapped_andLikeEscaped() {
    UUID userId = UUID.randomUUID();
    stubSearchReturning();
    service()
        .searchLibrary(
            userId,
            new RecipeSearchCriteriaDto(null, "Chi%ck_en!", null, null, null, false),
            PageRequest.of(0, 20));
    verify(recipeRepository)
        .searchLibrary(
            eq(userId),
            eq(true),
            eq(true),
            eq(false),
            eq("%chi!%ck!_en!!%"),
            eq(null),
            eq(null),
            anyCollection(),
            any(Pageable.class));
  }

  @Test
  void blankNamePattern_meansNoNamePredicate() {
    UUID userId = UUID.randomUUID();
    stubSearchReturning();
    service()
        .searchLibrary(
            userId,
            new RecipeSearchCriteriaDto(null, "   ", null, null, null, false),
            PageRequest.of(0, 20));
    verify(recipeRepository)
        .searchLibrary(
            eq(userId),
            eq(true),
            eq(true),
            eq(false),
            eq(null),
            eq(null),
            eq(null),
            anyCollection(),
            any(Pageable.class));
  }

  @Test
  void minDataQuality_expandsToTheOrdinalFloorSet() {
    UUID userId = UUID.randomUUID();
    stubSearchReturning();
    service()
        .searchLibrary(
            userId,
            new RecipeSearchCriteriaDto(null, null, "thai", 30, DataQuality.IMPORTED, false),
            PageRequest.of(0, 20));
    verify(recipeRepository)
        .searchLibrary(
            eq(userId),
            eq(true),
            eq(true),
            eq(false),
            eq(null),
            eq("thai"),
            eq(30),
            qualitiesCaptor.capture(),
            any(Pageable.class));
    assertThat(qualitiesCaptor.getValue())
        .containsExactlyInAnyOrder(
            DataQuality.USER_VERIFIED, DataQuality.IMPORTED, DataQuality.AI_GENERATED);
  }

  @Test
  void ratingAggregate_isMergedPerRow_unratedRowsGetNullAndZero() {
    UUID userId = UUID.randomUUID();
    UUID ratedId = UUID.randomUUID();
    UUID unratedId = UUID.randomUUID();
    stubSearchReturning(recipe(ratedId, userId), recipe(unratedId, userId));
    when(ratingRepository.aggregateTasteForRecipes(anyCollection()))
        .thenReturn(List.<Object[]>of(new Object[] {ratedId, 78.5d, 4L}));

    Page<RecipeDto> page =
        service()
            .searchLibrary(
                userId,
                new RecipeSearchCriteriaDto(null, null, null, null, null, false),
                PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(2);
    RecipeDto rated =
        page.getContent().stream().filter(d -> d.id().equals(ratedId)).findFirst().orElseThrow();
    RecipeDto unrated =
        page.getContent().stream().filter(d -> d.id().equals(unratedId)).findFirst().orElseThrow();
    assertThat(rated.avgTaste()).isEqualTo(78.5d);
    assertThat(rated.ratingCount()).isEqualTo(4L);
    assertThat(unrated.avgTaste()).isNull();
    assertThat(unrated.ratingCount()).isZero();
  }

  @Test
  void emptyPage_returnsEmptyEnvelope_withoutHydrationQueries() {
    UUID userId = UUID.randomUUID();
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
        .thenAnswer(inv -> new PageImpl<Recipe>(List.of(), inv.getArgument(8, Pageable.class), 0));

    Page<RecipeDto> page =
        service()
            .searchLibrary(
                userId,
                new RecipeSearchCriteriaDto(null, null, null, null, null, false),
                PageRequest.of(0, 20));

    assertThat(page.getTotalElements()).isZero();
    assertThat(page.getContent()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(branchRepository, versionRepository, ratingRepository);
  }
}
