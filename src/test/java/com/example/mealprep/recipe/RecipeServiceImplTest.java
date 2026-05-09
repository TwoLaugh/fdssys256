package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.mapper.IngredientMapper;
import com.example.mealprep.recipe.api.mapper.MethodStepMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMetadataMapper;
import com.example.mealprep.recipe.api.mapper.RecipeTagsMapper;
import com.example.mealprep.recipe.api.mapper.RecipeVersionMapper;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.repository.RecipeBranchRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.internal.RecipeServiceImpl;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.example.mealprep.recipe.event.RecipeVersionCreatedEvent;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
  @Mock private ApplicationEventPublisher eventPublisher;

  private final IngredientMapper ingredientMapper = new IngredientMapper();
  private final MethodStepMapper methodStepMapper = new MethodStepMapper();
  private final RecipeMetadataMapper metadataMapper = new RecipeMetadataMapper();
  private final RecipeTagsMapper tagsMapper = new RecipeTagsMapper();

  private final RecipeVersionMapper versionMapper =
      new RecipeVersionMapper(ingredientMapper, methodStepMapper, metadataMapper, tagsMapper);
  private final RecipeMapper recipeMapper = new RecipeMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);

  private RecipeServiceImpl service() {
    return new RecipeServiceImpl(
        recipeRepository,
        branchRepository,
        versionRepository,
        recipeMapper,
        versionMapper,
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

    // Verify the recipe.currentBranchId mutation actually happened on the managed entity
    // BEFORE we hand the version off — the service captures it via dirty-check, then
    // saveAndFlush flushes the UPDATE.
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
}
