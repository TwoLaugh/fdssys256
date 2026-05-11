package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
import com.example.mealprep.recipe.api.mapper.IngredientMapper;
import com.example.mealprep.recipe.api.mapper.MethodStepMapper;
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
import com.example.mealprep.recipe.domain.entity.RecipeSubstitution;
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
import com.example.mealprep.recipe.event.RecipeSubstitutionStateChangedEvent;
import com.example.mealprep.recipe.exception.RecipeSubstitutionNotFoundException;
import com.example.mealprep.recipe.exception.SubstitutionPromotionPreconditionException;
import com.example.mealprep.recipe.exception.SubstitutionRecordPreconditionException;
import com.example.mealprep.recipe.exception.SubstitutionTerminalStateException;
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

/**
 * Unit tests for the recipe-01e substitution flows on {@link RecipeServiceImpl}. Repository mocks
 * isolate behaviour from the database; the real {@link RecipeSubstitutionMapper} and {@link
 * SubstitutionOverlayApplier} are used because they are deterministic and central to behaviour.
 */
@ExtendWith(MockitoExtension.class)
class RecipeSubstitutionsServiceTest {

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
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);

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
        new com.example.mealprep.recipe.api.mapper.ParsedRecipeToCreateRequestMapper(),
        versionDiffer,
        divergenceCalculator,
        fingerprintDeriver,
        overlayApplier,
        objectMapper,
        eventPublisher,
        fixedClock);
  }

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

  private static RecipeSubstitution proposedSub(UUID recipeId) {
    return RecipeSubstitution.builder()
        .id(UUID.randomUUID())
        .recipeId(recipeId)
        .versionId(UUID.randomUUID())
        .branchId(UUID.randomUUID())
        .originalMappingKey("beef.mince")
        .originalQuantity(new BigDecimal("500.000"))
        .originalUnit("g")
        .substituteMappingKey("soy.crumble")
        .substituteQuantity(new BigDecimal("400.000"))
        .substituteUnit("g")
        .reason(SubstitutionReason.DIETARY_TEMP)
        .temporary(true)
        .applicationCount(0)
        .state(SubstitutionState.PROPOSED)
        .createdAt(Instant.parse("2026-05-09T09:30:00Z"))
        .createdByActor("user:test")
        .version(0L)
        .build();
  }

  @Test
  void acceptSubstitution_fromProposed_transitionsAndPublishesEvent() {
    UUID userId = UUID.randomUUID();
    Recipe recipe = userRecipe(userId);
    RecipeSubstitution sub = proposedSub(recipe.getId());
    when(substitutionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipe.getId()))
        .thenReturn(Optional.of(recipe));
    when(substitutionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    RecipeSubstitutionDto result = service().acceptSubstitution(sub.getId(), userId, 0L);

    assertThat(result.state()).isEqualTo(SubstitutionState.ACCEPTED);
    verify(eventPublisher).publishEvent(any(RecipeSubstitutionStateChangedEvent.class));
  }

  @Test
  void acceptSubstitution_alreadyAccepted_isNoOp_noEvent() {
    UUID userId = UUID.randomUUID();
    Recipe recipe = userRecipe(userId);
    RecipeSubstitution sub = proposedSub(recipe.getId());
    sub.setState(SubstitutionState.ACCEPTED);
    when(substitutionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipe.getId()))
        .thenReturn(Optional.of(recipe));

    RecipeSubstitutionDto result = service().acceptSubstitution(sub.getId(), userId, 0L);

    assertThat(result.state()).isEqualTo(SubstitutionState.ACCEPTED);
    verify(eventPublisher, never()).publishEvent(any(RecipeSubstitutionStateChangedEvent.class));
    verify(substitutionRepository, never()).saveAndFlush(any());
  }

  @Test
  void acceptSubstitution_superseded_throws422() {
    UUID userId = UUID.randomUUID();
    Recipe recipe = userRecipe(userId);
    RecipeSubstitution sub = proposedSub(recipe.getId());
    sub.setState(SubstitutionState.SUPERSEDED);
    when(substitutionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipe.getId()))
        .thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().acceptSubstitution(sub.getId(), userId, 0L))
        .isInstanceOf(SubstitutionTerminalStateException.class);
  }

  @Test
  void rejectSubstitution_alreadyRejected_isNoOp_noEvent() {
    UUID userId = UUID.randomUUID();
    Recipe recipe = userRecipe(userId);
    RecipeSubstitution sub = proposedSub(recipe.getId());
    sub.setState(SubstitutionState.REJECTED);
    when(substitutionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipe.getId()))
        .thenReturn(Optional.of(recipe));

    service().rejectSubstitution(sub.getId(), userId, 0L, null);

    verify(eventPublisher, never()).publishEvent(any(RecipeSubstitutionStateChangedEvent.class));
  }

  @Test
  void promoteSubstitution_fromProposed_throws422() {
    UUID userId = UUID.randomUUID();
    Recipe recipe = userRecipe(userId);
    RecipeSubstitution sub = proposedSub(recipe.getId());
    when(substitutionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
    when(recipeRepository.findByIdAndDeletedAtIsNull(recipe.getId()))
        .thenReturn(Optional.of(recipe));

    assertThatThrownBy(() -> service().promoteSubstitutionToVersion(sub.getId(), userId, 0L, "x"))
        .isInstanceOf(SubstitutionPromotionPreconditionException.class);
  }

  @Test
  void recordSubstitution_missing_throws404() {
    UUID id = UUID.randomUUID();
    when(substitutionRepository.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().recordSubstitution(id, UUID.randomUUID()))
        .isInstanceOf(RecipeSubstitutionNotFoundException.class);
  }

  @Test
  void recordSubstitution_notAccepted_throws422() {
    RecipeSubstitution sub = proposedSub(UUID.randomUUID());
    when(substitutionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
    assertThatThrownBy(() -> service().recordSubstitution(sub.getId(), UUID.randomUUID()))
        .isInstanceOf(SubstitutionRecordPreconditionException.class);
  }

  @Test
  void recordSubstitution_appendsPlanId_andBumpsCount() {
    RecipeSubstitution sub = proposedSub(UUID.randomUUID());
    sub.setState(SubstitutionState.ACCEPTED);
    sub.setAppliedInPlanIds(new UUID[0]);
    when(substitutionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
    UUID planId = UUID.randomUUID();

    service().recordSubstitution(sub.getId(), planId);

    assertThat(sub.getAppliedInPlanIds()).containsExactly(planId);
    assertThat(sub.getApplicationCount()).isEqualTo(1);
    assertThat(sub.getLastAppliedAt()).isNotNull();
  }

  @Test
  void recordSubstitution_isIdempotent_onDuplicatePlanId() {
    RecipeSubstitution sub = proposedSub(UUID.randomUUID());
    sub.setState(SubstitutionState.ACCEPTED);
    UUID planId = UUID.randomUUID();
    sub.setAppliedInPlanIds(new UUID[] {planId});
    sub.setApplicationCount(1);
    when(substitutionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));

    service().recordSubstitution(sub.getId(), planId);

    assertThat(sub.getAppliedInPlanIds()).containsExactly(planId);
    assertThat(sub.getApplicationCount()).isEqualTo(1);
  }
}
