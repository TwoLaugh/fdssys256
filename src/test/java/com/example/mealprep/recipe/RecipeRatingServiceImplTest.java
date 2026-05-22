package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.CreateRatingRequest;
import com.example.mealprep.recipe.api.dto.RecipeRatingDto;
import com.example.mealprep.recipe.api.dto.UpdateRatingRequest;
import com.example.mealprep.recipe.api.mapper.RecipeRatingMapper;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeRating;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.repository.RecipeRatingRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.internal.RecipeRatingServiceImpl;
import com.example.mealprep.recipe.event.RecipeRatingFiredEvent;
import com.example.mealprep.recipe.exception.DuplicateRecipeRatingException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.exception.RecipeRatingNotFoundException;
import com.example.mealprep.recipe.exception.RecipeRatingValidationException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Unit tests for the recipe-02b rating write flows on {@link RecipeRatingServiceImpl}. Repository
 * mocks isolate from the DB; the real {@link RecipeRatingMapper} is used (deterministic).
 */
@ExtendWith(MockitoExtension.class)
class RecipeRatingServiceImplTest {

  @Mock private RecipeRatingRepository ratingRepository;
  @Mock private RecipeVersionRepository versionRepository;
  @Mock private RecipeRepository recipeRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final RecipeRatingMapper ratingMapper = new RecipeRatingMapperTestImpl();
  private final Clock clock = Clock.fixed(Instant.parse("2026-05-22T10:00:00Z"), ZoneOffset.UTC);

  private RecipeRatingServiceImpl service;

  private final UUID userId = UUID.randomUUID();
  private final UUID recipeId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new RecipeRatingServiceImpl(
            ratingRepository,
            versionRepository,
            recipeRepository,
            ratingMapper,
            eventPublisher,
            clock);
  }

  private RecipeVersion versionBelongingToRecipe() {
    Recipe recipe = Recipe.builder().id(recipeId).build();
    return RecipeVersion.builder().id(versionId).recipe(recipe).build();
  }

  private void stubRecipeAndVersionOk() {
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId))
        .thenReturn(Optional.of(Recipe.builder().id(recipeId).build()));
    when(versionRepository.findById(versionId)).thenReturn(Optional.of(versionBelongingToRecipe()));
  }

  @Test
  void create_persistsComputesAggregateAndFiresEvent() {
    stubRecipeAndVersionOk();
    when(ratingRepository.findByVersionIdAndUserId(versionId, userId)).thenReturn(Optional.empty());
    when(ratingRepository.saveAndFlush(any(RecipeRating.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CreateRatingRequest req = new CreateRatingRequest(versionId, null, 85, 70, 90, 75, "notes");
    RecipeRatingDto dto = service.create(userId, recipeId, req);

    assertThat(dto.taste()).isEqualTo(85);
    assertThat(dto.aggregate()).isEqualTo(80);
    assertThat(dto.recipeId()).isEqualTo(recipeId);
    assertThat(dto.versionId()).isEqualTo(versionId);

    ArgumentCaptor<RecipeRatingFiredEvent> evt =
        ArgumentCaptor.forClass(RecipeRatingFiredEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().userId()).isEqualTo(userId);
    assertThat(evt.getValue().aggregate()).isEqualTo(80);
    assertThat(evt.getValue().origin()).isNotNull();
  }

  @Test
  void create_oneTap_aggregateEqualsTaste() {
    stubRecipeAndVersionOk();
    when(ratingRepository.findByVersionIdAndUserId(versionId, userId)).thenReturn(Optional.empty());
    when(ratingRepository.saveAndFlush(any(RecipeRating.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    RecipeRatingDto dto =
        service.create(
            userId, recipeId, new CreateRatingRequest(versionId, null, 80, null, null, null, null));
    assertThat(dto.aggregate()).isEqualTo(80);
  }

  @Test
  void create_duplicateForUserVersion_throws409() {
    stubRecipeAndVersionOk();
    when(ratingRepository.findByVersionIdAndUserId(versionId, userId))
        .thenReturn(Optional.of(RecipeRating.builder().id(UUID.randomUUID()).build()));

    assertThatThrownBy(
            () ->
                service.create(
                    userId,
                    recipeId,
                    new CreateRatingRequest(versionId, null, 80, null, null, null, null)))
        .isInstanceOf(DuplicateRecipeRatingException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void create_recipeMissing_throws404() {
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    userId,
                    recipeId,
                    new CreateRatingRequest(versionId, null, 80, null, null, null, null)))
        .isInstanceOf(RecipeNotFoundException.class);
  }

  @Test
  void create_versionMissing_throws404() {
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId))
        .thenReturn(Optional.of(Recipe.builder().id(recipeId).build()));
    when(versionRepository.findById(versionId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    userId,
                    recipeId,
                    new CreateRatingRequest(versionId, null, 80, null, null, null, null)))
        .isInstanceOf(RecipeVersionNotFoundException.class);
  }

  @Test
  void create_versionBelongsToOtherRecipe_throws400() {
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipeId))
        .thenReturn(Optional.of(Recipe.builder().id(recipeId).build()));
    Recipe otherRecipe = Recipe.builder().id(UUID.randomUUID()).build();
    when(versionRepository.findById(versionId))
        .thenReturn(Optional.of(RecipeVersion.builder().id(versionId).recipe(otherRecipe).build()));

    assertThatThrownBy(
            () ->
                service.create(
                    userId,
                    recipeId,
                    new CreateRatingRequest(versionId, null, 80, null, null, null, null)))
        .isInstanceOf(RecipeRatingValidationException.class);
  }

  @Test
  void update_recomputesAggregateAndFiresEvent() {
    UUID ratingId = UUID.randomUUID();
    RecipeRating existing =
        RecipeRating.builder()
            .id(ratingId)
            .recipeId(recipeId)
            .versionId(versionId)
            .userId(userId)
            .taste(50)
            .aggregate(50)
            .optimisticVersion(0L)
            .build();
    when(ratingRepository.findByIdAndUserId(ratingId, userId)).thenReturn(Optional.of(existing));
    stubRecipeAndVersionOk();
    when(ratingRepository.saveAndFlush(any(RecipeRating.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateRatingRequest req =
        new UpdateRatingRequest(versionId, null, 90, null, null, null, null, 0L);
    RecipeRatingDto dto = service.update(userId, recipeId, ratingId, req);

    assertThat(dto.taste()).isEqualTo(90);
    assertThat(dto.aggregate()).isEqualTo(90);
    verify(eventPublisher).publishEvent(any(RecipeRatingFiredEvent.class));
  }

  @Test
  void update_staleExpectedVersion_throwsOptimisticLock() {
    UUID ratingId = UUID.randomUUID();
    RecipeRating existing =
        RecipeRating.builder()
            .id(ratingId)
            .recipeId(recipeId)
            .versionId(versionId)
            .userId(userId)
            .taste(50)
            .aggregate(50)
            .optimisticVersion(3L)
            .build();
    when(ratingRepository.findByIdAndUserId(ratingId, userId)).thenReturn(Optional.of(existing));
    stubRecipeAndVersionOk();

    UpdateRatingRequest req =
        new UpdateRatingRequest(versionId, null, 90, null, null, null, null, 0L);
    assertThatThrownBy(() -> service.update(userId, recipeId, ratingId, req))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void update_notOwner_throws404() {
    UUID ratingId = UUID.randomUUID();
    when(ratingRepository.findByIdAndUserId(ratingId, userId)).thenReturn(Optional.empty());

    UpdateRatingRequest req =
        new UpdateRatingRequest(versionId, null, 90, null, null, null, null, 0L);
    assertThatThrownBy(() -> service.update(userId, recipeId, ratingId, req))
        .isInstanceOf(RecipeRatingNotFoundException.class);
  }

  @Test
  void delete_notOwner_throws404_andNoEvent() {
    UUID ratingId = UUID.randomUUID();
    when(ratingRepository.findByIdAndUserId(ratingId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(userId, recipeId, ratingId))
        .isInstanceOf(RecipeRatingNotFoundException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void delete_owner_deletesAndDoesNotFireEvent() {
    UUID ratingId = UUID.randomUUID();
    RecipeRating existing =
        RecipeRating.builder().id(ratingId).recipeId(recipeId).userId(userId).build();
    when(ratingRepository.findByIdAndUserId(ratingId, userId)).thenReturn(Optional.of(existing));

    service.delete(userId, recipeId, ratingId);

    verify(ratingRepository).delete(existing);
    verify(eventPublisher, never()).publishEvent(any());
  }

  /**
   * Hand-rolled mapper impl so the unit test needs no MapStruct-generated class on the classpath.
   */
  private static final class RecipeRatingMapperTestImpl implements RecipeRatingMapper {}
}
