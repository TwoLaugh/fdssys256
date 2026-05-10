package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.mapper.ParsedRecipeToCreateRequestMapper;
import com.example.mealprep.recipe.api.mapper.RecipeBranchMapper;
import com.example.mealprep.recipe.api.mapper.RecipeImportMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMapper;
import com.example.mealprep.recipe.api.mapper.RecipeVersionMapper;
import com.example.mealprep.recipe.config.UrlFetcher;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.ImportSource;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeImport;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeMethodStep;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.repository.RecipeBranchRepository;
import com.example.mealprep.recipe.domain.repository.RecipeImportRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.example.mealprep.recipe.event.RecipeVersionCreatedEvent;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of {@link RecipeQueryService} and {@link RecipeUpdateService}.
 *
 * <p>Reads run with {@code readOnly = true}; writes run REQUIRED. The version body has two
 * {@code @OneToMany List<>} children — multi-attribute {@code @EntityGraph} would throw {@code
 * MultipleBagFetchException}, so {@code getById} touches each list inside the transaction to force
 * lazy load. The mapper applies explicit {@code Comparator} ordering when building DTO lists.
 *
 * <p>Create flow: insert {@code Recipe} (currentBranchId=null) + {@code RecipeBranch}, then {@code
 * recipe.setCurrentBranchId(branch.id)} — JPA dirty-check picks it up; one extra UPDATE on commit.
 * Version + body insert next, all in the same transaction. {@code saveAndFlush} so
 * {@code @CreationTimestamp} and {@code @Version} materialise before the response DTO.
 *
 * <p>recipe-01b layered on URL imports + the branches lookup endpoint. {@link #importFromUrl}
 * reuses the manual-create write path via the {@link #createRecipeInternal} overload (which lets
 * callers override {@code dataQuality} + {@code VersionTrigger}), then writes a single {@code
 * RecipeImport} provenance row in the same transaction. {@link #getById} now also runs {@code
 * findAllByRecipeId} so {@code branches[]} is populated on every read.
 */
@Service
public class RecipeServiceImpl implements RecipeQueryService, RecipeUpdateService {

  private static final Logger log = LoggerFactory.getLogger(RecipeServiceImpl.class);

  private static final String MDC_TRACE_ID = "traceId";
  private static final String MAIN_BRANCH_NAME = "main";
  private static final String EMBEDDING_STATUS_PENDING = "pending";

  private final RecipeRepository recipeRepository;
  private final RecipeBranchRepository branchRepository;
  private final RecipeVersionRepository versionRepository;
  private final RecipeImportRepository importRepository;
  private final RecipeMapper recipeMapper;
  private final RecipeVersionMapper versionMapper;
  private final RecipeBranchMapper branchMapper;
  private final RecipeImportMapper importMapper;
  private final UrlFetcher urlFetcher;
  private final HtmlImportParser htmlImportParser;
  private final ParsedRecipeToCreateRequestMapper parserToCreateRequestMapper;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public RecipeServiceImpl(
      RecipeRepository recipeRepository,
      RecipeBranchRepository branchRepository,
      RecipeVersionRepository versionRepository,
      RecipeImportRepository importRepository,
      RecipeMapper recipeMapper,
      RecipeVersionMapper versionMapper,
      RecipeBranchMapper branchMapper,
      RecipeImportMapper importMapper,
      UrlFetcher urlFetcher,
      HtmlImportParser htmlImportParser,
      ParsedRecipeToCreateRequestMapper parserToCreateRequestMapper,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.recipeRepository = recipeRepository;
    this.branchRepository = branchRepository;
    this.versionRepository = versionRepository;
    this.importRepository = importRepository;
    this.recipeMapper = recipeMapper;
    this.versionMapper = versionMapper;
    this.branchMapper = branchMapper;
    this.importMapper = importMapper;
    this.urlFetcher = urlFetcher;
    this.htmlImportParser = htmlImportParser;
    this.parserToCreateRequestMapper = parserToCreateRequestMapper;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<RecipeDto> getById(UUID recipeId) {
    return recipeRepository
        .findByIdAndDeletedAtIsNull(recipeId)
        .map(
            recipe -> {
              List<RecipeBranchDto> branches =
                  branchMapper.toDtoList(branchRepository.findAllByRecipeId(recipe.getId()));
              if (recipe.getCurrentBranchId() == null) {
                // Should never happen for a created-via-API recipe; defensive.
                return recipeMapper.toDto(recipe, null, branches);
              }
              Optional<RecipeVersion> versionOpt =
                  versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(
                      recipe.getId(), recipe.getCurrentBranchId(), recipe.getCurrentVersion());
              if (versionOpt.isEmpty()) {
                return recipeMapper.toDto(recipe, null, branches);
              }
              RecipeVersion version = versionOpt.get();
              // Force lazy-load of the two list children + the two @OneToOne children inside the
              // read-only tx. Multi-attribute @EntityGraph is unsafe (MultipleBagFetchException).
              version.getIngredients().size();
              version.getMethodSteps().size();
              @SuppressWarnings("unused")
              RecipeMetadata metadata = version.getMetadata();
              @SuppressWarnings("unused")
              RecipeTags tags = version.getTags();
              if (metadata != null) {
                metadata.getEquipmentRequired().size();
                metadata.getMealTypes().size();
              }
              if (tags != null) {
                tags.getFlavourProfile().size();
                tags.getDietaryFlags().size();
              }
              RecipeVersionDto versionDto = versionMapper.toDto(version);
              return recipeMapper.toDto(recipe, versionDto, branches);
            });
  }

  @Override
  @Transactional(readOnly = true)
  public List<RecipeBranchDto> getBranches(UUID recipeId) {
    Recipe recipe =
        recipeRepository
            .findByIdAndDeletedAtIsNull(recipeId)
            .orElseThrow(() -> new RecipeNotFoundException(recipeId));
    return branchMapper.toDtoList(branchRepository.findAllByRecipeId(recipe.getId()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RecipeImportDto> getImportProvenance(UUID recipeId) {
    return importRepository.findByRecipeId(recipeId).map(importMapper::toDto);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public RecipeDto createRecipe(UUID userId, CreateRecipeRequest request) {
    return createRecipeInternal(
        userId, request, DataQuality.USER_VERIFIED, VersionTrigger.MANUAL_CREATE);
  }

  @Override
  @Transactional
  public RecipeDto importFromUrl(UUID userId, ImportRecipeFromUrlRequest request) {
    String html = urlFetcher.fetch(request.url());
    HtmlImportParser.ParsedRecipe parsed = htmlImportParser.parse(html, request.url());
    CreateRecipeRequest mapped = parserToCreateRequestMapper.map(parsed);
    // TODO recipe-01g — dedupe on import (DeduplicationFingerprintHasher).
    RecipeDto created =
        createRecipeInternal(userId, mapped, DataQuality.IMPORTED, VersionTrigger.IMPORT);
    RecipeImport provenance =
        RecipeImport.builder()
            .id(UUID.randomUUID())
            .recipeId(created.id())
            .sourceType(ImportSource.URL)
            .sourceUrl(request.url())
            .sourcePayload(parsed.rawPayload())
            .extractionMethod(parsed.extractionMethod())
            .duplicateOfRecipeId(null)
            .importedAt(Instant.now(clock))
            .importedByUserId(userId)
            .build();
    importRepository.save(provenance);
    // Re-hydrate so branches[] reflects the just-created 'main' branch via the same mapper path.
    return getById(created.id()).orElse(created);
  }

  /**
   * Internal write path used by both the public {@link #createRecipe} (manual_create) and {@link
   * #importFromUrl} (URL import). Caller picks the {@link DataQuality} and {@link VersionTrigger}.
   */
  private RecipeDto createRecipeInternal(
      UUID userId, CreateRecipeRequest request, DataQuality dataQuality, VersionTrigger trigger) {
    Instant now = Instant.now(clock);
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID traceId = currentTraceId();
    String createdByActor = "user:" + userId;

    Recipe recipe =
        Recipe.builder()
            .id(recipeId)
            .userId(userId)
            .catalogue(Catalogue.USER)
            .name(request.name())
            .description(request.description())
            .currentVersion(1)
            .currentBranchId(null)
            .dataQuality(dataQuality)
            .nutritionStatus(NutritionStatus.PENDING)
            .build();
    recipe = recipeRepository.save(recipe);

    RecipeBranch branch =
        RecipeBranch.builder()
            .id(branchId)
            .recipe(recipe)
            .parentBranchId(null)
            .branchPointVersionId(null)
            .name(MAIN_BRANCH_NAME)
            .label(null)
            .reason(null)
            .currentVersion(1)
            .divergenceScore(new BigDecimal("0.000"))
            .createdByActor(createdByActor)
            .adapterTraceId(null)
            .build();
    branch = branchRepository.save(branch);

    recipe.setCurrentBranchId(branch.getId());

    RecipeVersion version =
        RecipeVersion.builder()
            .id(versionId)
            .recipe(recipe)
            .branch(branch)
            .versionNumber(1)
            .parentVersionId(null)
            .changeDiff(JsonNodeFactory.instance.objectNode())
            .changeReason(null)
            .trigger(trigger)
            .characterFingerprint(null)
            .nutritionPerServing(null)
            .embeddingStatus(EMBEDDING_STATUS_PENDING)
            .createdByActor(createdByActor)
            .adapterTraceId(null)
            .ingredients(new ArrayList<>())
            .methodSteps(new ArrayList<>())
            .build();

    populateIngredients(version, request.ingredients());
    populateMethodSteps(version, request.method());
    version.setMetadata(buildMetadata(version, request.metadata()));
    version.setTags(buildTags(version, request.tags()));

    RecipeVersion savedVersion = versionRepository.saveAndFlush(version);
    Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);

    eventPublisher.publishEvent(
        new RecipeCreatedEvent(savedRecipe.getId(), savedRecipe.getCatalogue(), traceId, now));
    eventPublisher.publishEvent(
        new RecipeVersionCreatedEvent(
            savedVersion.getId(),
            savedRecipe.getId(),
            branch.getId(),
            savedVersion.getVersionNumber(),
            traceId,
            now));

    log.info(
        "recipe created recipeId={} branchId={} versionId={} userId={} trigger={}",
        savedRecipe.getId(),
        branch.getId(),
        savedVersion.getId(),
        userId,
        trigger);

    RecipeVersionDto versionDto = versionMapper.toDto(savedVersion);
    List<RecipeBranchDto> branches = List.of(branchMapper.toDto(branch));
    return recipeMapper.toDto(savedRecipe, versionDto, branches);
  }

  // ---------------- Helpers ----------------

  private static void populateIngredients(
      RecipeVersion version, List<CreateIngredientRequest> requests) {
    if (requests == null) {
      return;
    }
    for (CreateIngredientRequest dto : requests) {
      RecipeIngredient ingredient =
          RecipeIngredient.builder()
              .id(UUID.randomUUID())
              .version(version)
              .lineOrder(dto.lineOrder())
              .ingredientMappingKey(dto.ingredientMappingKey())
              .displayName(dto.displayName())
              .quantity(dto.quantity())
              .unit(dto.unit())
              .preparation(dto.preparation())
              .optional(dto.optional() != null && dto.optional())
              .needsReview(false)
              .mappingConfidence(null)
              .build();
      version.getIngredients().add(ingredient);
    }
  }

  private static void populateMethodSteps(
      RecipeVersion version, List<CreateMethodStepRequest> requests) {
    if (requests == null) {
      return;
    }
    for (CreateMethodStepRequest dto : requests) {
      RecipeMethodStep step =
          RecipeMethodStep.builder()
              .id(UUID.randomUUID())
              .version(version)
              .stepNumber(dto.stepNumber())
              .instruction(dto.instruction())
              .durationMinutes(dto.durationMinutes())
              .build();
      version.getMethodSteps().add(step);
    }
  }

  private static RecipeMetadata buildMetadata(
      RecipeVersion version, CreateRecipeMetadataRequest request) {
    return RecipeMetadata.builder()
        .id(UUID.randomUUID())
        .version(version)
        .servings(request.servings())
        .prepTimeMins(request.prepTimeMins())
        .cookTimeMins(request.cookTimeMins())
        .totalTimeMins(request.totalTimeMins())
        .equipmentRequired(
            request.equipmentRequired() != null
                ? new ArrayList<>(request.equipmentRequired())
                : new ArrayList<>())
        .fridgeDays(request.fridgeDays())
        .freezerWeeks(request.freezerWeeks())
        .packable(request.packable() != null && request.packable())
        .cuisine(request.cuisine())
        .mealTypes(
            request.mealTypes() != null ? new ArrayList<>(request.mealTypes()) : new ArrayList<>())
        .build();
  }

  private static RecipeTags buildTags(RecipeVersion version, CreateRecipeTagsRequest request) {
    if (request == null) {
      return RecipeTags.builder()
          .id(UUID.randomUUID())
          .version(version)
          .protein(null)
          .cookingMethod(null)
          .complexity(null)
          .flavourProfile(new ArrayList<>())
          .dietaryFlags(new ArrayList<>())
          .build();
    }
    return RecipeTags.builder()
        .id(UUID.randomUUID())
        .version(version)
        .protein(request.protein())
        .cookingMethod(request.cookingMethod())
        .complexity(request.complexity())
        .flavourProfile(
            request.flavourProfile() != null
                ? new ArrayList<>(request.flavourProfile())
                : new ArrayList<>())
        .dietaryFlags(
            request.dietaryFlags() != null
                ? new ArrayList<>(request.dietaryFlags())
                : new ArrayList<>())
        .build();
  }

  private static UUID currentTraceId() {
    String fromMdc = MDC.get(MDC_TRACE_ID);
    if (fromMdc != null && !fromMdc.isBlank()) {
      try {
        return UUID.fromString(fromMdc);
      } catch (IllegalArgumentException ignored) {
        // MDC value isn't a UUID — fall through to randomUUID.
      }
    }
    return UUID.randomUUID();
  }
}
