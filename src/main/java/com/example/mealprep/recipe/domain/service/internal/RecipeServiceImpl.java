package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateBranchRequest;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.api.dto.CreateSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.MethodOverlayLineRequest;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import com.example.mealprep.recipe.api.mapper.ParsedRecipeToCreateRequestMapper;
import com.example.mealprep.recipe.api.mapper.RecipeBranchMapper;
import com.example.mealprep.recipe.api.mapper.RecipeDiffMapper;
import com.example.mealprep.recipe.api.mapper.RecipeImportMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMapper;
import com.example.mealprep.recipe.api.mapper.RecipeSubstitutionMapper;
import com.example.mealprep.recipe.api.mapper.RecipeVersionMapper;
import com.example.mealprep.recipe.config.UrlFetcher;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.ImportSource;
import com.example.mealprep.recipe.domain.entity.MethodOverlayLine;
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
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeSubstitutionRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.event.AdaptationOutcomeType;
import com.example.mealprep.recipe.event.ArchiveCause;
import com.example.mealprep.recipe.event.RecipeAdaptedEvent;
import com.example.mealprep.recipe.event.RecipeArchivedEvent;
import com.example.mealprep.recipe.event.RecipeBranchCreatedEvent;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.example.mealprep.recipe.event.RecipeEvolvedEvent;
import com.example.mealprep.recipe.event.RecipeEvolvedEvent.EvolvedReason;
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
import com.example.mealprep.recipe.exception.RecipeImportFailedException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.exception.RecipeSubstitutionNotFoundException;
import com.example.mealprep.recipe.exception.RecipeVersionConflictException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import com.example.mealprep.recipe.exception.SubstitutionOriginalNotInVersionException;
import com.example.mealprep.recipe.exception.SubstitutionPromotionPreconditionException;
import com.example.mealprep.recipe.exception.SubstitutionRecordPreconditionException;
import com.example.mealprep.recipe.exception.SubstitutionTerminalStateException;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import com.example.mealprep.recipe.spi.ImportedRecipeResult;
import com.example.mealprep.recipe.spi.RecipeSubstitutionRecorder;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedBranchCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedSubstitutionCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
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
 *
 * <p>recipe-01d adds: {@link #getBranch} (single-branch lookup), {@link #getFingerprint} (internal
 * cross-module helper, no REST), {@link #createBranch} (user-facing fork + provisional jaccard-mean
 * divergence score + minimal-derivation fingerprint) and {@link #revertToVersion} (writes a new
 * version cloned from the target body; trigger = REVERT). 01d does NOT mutate {@code
 * Recipe.currentBranchId} on branch creation — checkout is a separate flow (recipe-01g).
 */
@Service
public class RecipeServiceImpl
    implements RecipeQueryService, RecipeUpdateService, RecipeSubstitutionRecorder, RecipeWriteApi {

  private static final Logger log = LoggerFactory.getLogger(RecipeServiceImpl.class);

  private static final String MDC_TRACE_ID = "traceId";
  private static final String MAIN_BRANCH_NAME = "main";
  private static final String EMBEDDING_STATUS_PENDING = "pending";

  /**
   * Sentinel user id for SYSTEM-catalogue rows created by the discovery pipeline. The {@code
   * recipe_recipes.user_id} column is NOT NULL but a {@code catalogue=SYSTEM} recipe has no owning
   * user; the nil UUID is the agreed sentinel (Origin tracking treats this as {@code
   * SYSTEM_DISCOVERY}).
   */
  private static final UUID SYSTEM_DISCOVERY_USER_ID = new UUID(0L, 0L);

  private final RecipeRepository recipeRepository;
  private final RecipeBranchRepository branchRepository;
  private final RecipeVersionRepository versionRepository;
  private final RecipeImportRepository importRepository;
  private final RecipeIngredientRepository ingredientRepository;
  private final RecipeSubstitutionRepository substitutionRepository;
  private final RecipeMapper recipeMapper;
  private final RecipeVersionMapper versionMapper;
  private final RecipeBranchMapper branchMapper;
  private final RecipeImportMapper importMapper;
  private final RecipeDiffMapper diffMapper;
  private final RecipeSubstitutionMapper substitutionMapper;
  private final UrlFetcher urlFetcher;
  private final HtmlImportParser htmlImportParser;
  private final ParsedRecipeToCreateRequestMapper parserToCreateRequestMapper;
  private final VersionDiffer versionDiffer;
  private final DivergenceScoreCalculator divergenceCalculator;
  private final FingerprintDeriver fingerprintDeriver;
  private final SubstitutionOverlayApplier overlayApplier;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public RecipeServiceImpl(
      RecipeRepository recipeRepository,
      RecipeBranchRepository branchRepository,
      RecipeVersionRepository versionRepository,
      RecipeImportRepository importRepository,
      RecipeIngredientRepository ingredientRepository,
      RecipeSubstitutionRepository substitutionRepository,
      RecipeMapper recipeMapper,
      RecipeVersionMapper versionMapper,
      RecipeBranchMapper branchMapper,
      RecipeImportMapper importMapper,
      RecipeDiffMapper diffMapper,
      RecipeSubstitutionMapper substitutionMapper,
      UrlFetcher urlFetcher,
      HtmlImportParser htmlImportParser,
      ParsedRecipeToCreateRequestMapper parserToCreateRequestMapper,
      VersionDiffer versionDiffer,
      DivergenceScoreCalculator divergenceCalculator,
      FingerprintDeriver fingerprintDeriver,
      SubstitutionOverlayApplier overlayApplier,
      ObjectMapper objectMapper,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.recipeRepository = recipeRepository;
    this.branchRepository = branchRepository;
    this.versionRepository = versionRepository;
    this.importRepository = importRepository;
    this.ingredientRepository = ingredientRepository;
    this.substitutionRepository = substitutionRepository;
    this.recipeMapper = recipeMapper;
    this.versionMapper = versionMapper;
    this.branchMapper = branchMapper;
    this.importMapper = importMapper;
    this.diffMapper = diffMapper;
    this.substitutionMapper = substitutionMapper;
    this.urlFetcher = urlFetcher;
    this.htmlImportParser = htmlImportParser;
    this.parserToCreateRequestMapper = parserToCreateRequestMapper;
    this.versionDiffer = versionDiffer;
    this.divergenceCalculator = divergenceCalculator;
    this.fingerprintDeriver = fingerprintDeriver;
    this.overlayApplier = overlayApplier;
    this.objectMapper = objectMapper;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<RecipeDto> getById(UUID recipeId) {
    return recipeRepository.findByIdAndDeletedAtIsNull(recipeId).map(this::hydrate);
  }

  @Override
  @Transactional(readOnly = true)
  public List<RecipeDto> findPlannableCandidates(UUID userId, int limit) {
    if (userId == null || limit <= 0) {
      return List.of();
    }
    List<Recipe> recipes =
        recipeRepository.findPlannableForUser(
            userId, org.springframework.data.domain.PageRequest.of(0, limit));
    List<RecipeDto> dtos = new ArrayList<>(recipes.size());
    for (Recipe recipe : recipes) {
      dtos.add(hydrate(recipe));
    }
    return dtos;
  }

  /**
   * Hydrate a loaded {@link Recipe} root to a full {@link RecipeDto} — branches[] + the
   * current-version body with its lazy children forced. SAME mapping the GET-by-id read path uses;
   * extracted so {@link #findPlannableCandidates} produces candidates identical in shape to {@link
   * #getById} (the planner's Stage-A hard filters read metadata + ingredient mapping keys, and the
   * beam search schedules {@code currentVersionBody().id()} + {@code currentBranchId()}).
   *
   * <p>Must run inside the read transaction (callers are {@code @Transactional(readOnly = true)})
   * so {@link #touchLazyChildren} can force-load the {@code @OneToMany} children before the session
   * closes (OSIV is off).
   */
  private RecipeDto hydrate(Recipe recipe) {
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
    touchLazyChildren(version);
    RecipeVersionDto versionDto = versionMapper.toDto(version);
    return recipeMapper.toDto(recipe, versionDto, branches);
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
  public Optional<RecipeBranchDto> getBranch(UUID recipeId, UUID branchId) {
    return branchRepository
        .findById(branchId)
        .filter(branch -> branch.getRecipe() != null && branch.getRecipe().getId().equals(recipeId))
        .map(branchMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CharacterFingerprintDto> getFingerprint(UUID recipeId, UUID branchId) {
    Optional<RecipeBranch> branchOpt =
        branchRepository
            .findById(branchId)
            .filter(b -> b.getRecipe() != null && b.getRecipe().getId().equals(recipeId));
    if (branchOpt.isEmpty()) {
      return Optional.empty();
    }
    RecipeBranch branch = branchOpt.get();
    return versionRepository
        .findFirstByRecipeIdAndBranchIdAndVersionNumber(
            recipeId, branch.getId(), branch.getCurrentVersion())
        .map(RecipeVersion::getCharacterFingerprint)
        .flatMap(this::jsonToFingerprint);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RecipeImportDto> getImportProvenance(UUID recipeId) {
    return importRepository.findByRecipeId(recipeId).map(importMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public RecipeDiffDto diff(UUID recipeId, UUID fromVersionId, UUID toVersionId) {
    RecipeVersion to =
        versionRepository
            .findById(toVersionId)
            .orElseThrow(() -> new RecipeVersionNotFoundException(toVersionId));
    if (to.getRecipe() == null || !to.getRecipe().getId().equals(recipeId)) {
      throw new RecipeVersionNotFoundException(toVersionId);
    }
    RecipeVersion from =
        versionRepository
            .findById(fromVersionId)
            .orElseThrow(() -> new RecipeVersionNotFoundException(fromVersionId));
    if (from.getBranch() == null
        || to.getBranch() == null
        || !from.getBranch().getId().equals(to.getBranch().getId())) {
      throw new RecipeDiffCrossBranchException();
    }
    if (!fromVersionId.equals(to.getParentVersionId())) {
      throw new RecipeDiffNotComputedException();
    }
    return diffMapper.fromJsonNode(fromVersionId, toVersionId, to.getChangeDiff());
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

  @Override
  @Transactional
  public RecipeDto manualEdit(
      UUID recipeId, UpdateRecipeManualEditRequest request, UUID actorUserId) {
    Recipe recipe = loadOwnedUserCatalogueRecipe(recipeId, actorUserId, "Manual edit");
    if (recipe.getOptimisticVersion() != request.expectedOptimisticVersion()) {
      throw new OptimisticLockingFailureException(
          "Stale expectedOptimisticVersion for recipe " + recipeId);
    }

    UUID currentVersionId =
        versionRepository
            .findCurrentVersionId(
                recipe.getId(), recipe.getCurrentBranchId(), recipe.getCurrentVersion())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Recipe inconsistent: no current version row for recipe " + recipeId));
    RecipeVersion parent =
        versionRepository
            .findById(currentVersionId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Recipe inconsistent: current version missing: " + currentVersionId));
    touchLazyChildren(parent);

    NewVersionInput requested =
        new NewVersionInput(
            request.ingredients(), request.method(), request.metadata(), request.tags());
    ObjectNode changeDiff = versionDiffer.diff(parent, requested);
    if (versionDiffer.isEmpty(changeDiff)) {
      throw new NoChangesException("Manual edit produced no diff against the current version.");
    }

    RecipeBranch currentBranch =
        branchRepository
            .findById(recipe.getCurrentBranchId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Recipe inconsistent: current branch missing: "
                            + recipe.getCurrentBranchId()));
    int newVersionNumber = parent.getVersionNumber() + 1;
    UUID newVersionId = UUID.randomUUID();
    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    String createdByActor = "user:" + actorUserId;

    RecipeVersion newVersion =
        RecipeVersion.builder()
            .id(newVersionId)
            .recipe(recipe)
            .branch(currentBranch)
            .versionNumber(newVersionNumber)
            .parentVersionId(parent.getId())
            .changeDiff(changeDiff)
            .changeReason(request.changeReason())
            .trigger(VersionTrigger.MANUAL_EDIT)
            .characterFingerprint(null)
            .nutritionPerServing(null)
            .embeddingStatus(EMBEDDING_STATUS_PENDING)
            .createdByActor(createdByActor)
            .adapterTraceId(null)
            .ingredients(new ArrayList<>())
            .methodSteps(new ArrayList<>())
            .build();

    populateIngredientsFromRequests(newVersion, request.ingredients());
    populateMethodStepsFromRequests(newVersion, request.method());
    newVersion.setMetadata(buildMetadataFromRequest(newVersion, request.metadata()));
    newVersion.setTags(buildTagsFromRequest(newVersion, request.tags()));

    RecipeVersion savedNewVersion = versionRepository.saveAndFlush(newVersion);

    // Update pointers — recipe + branch.
    recipe.setCurrentVersion(newVersionNumber);
    recipe.setName(request.name());
    recipe.setDescription(request.description());
    Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);

    currentBranch.setCurrentVersion(newVersionNumber);
    RecipeBranch savedBranch = branchRepository.saveAndFlush(currentBranch);

    eventPublisher.publishEvent(
        new RecipeVersionCreatedEvent(
            savedNewVersion.getId(),
            savedRecipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getVersionNumber(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeUpdatedEvent(
            savedRecipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getId(),
            savedNewVersion.getVersionNumber(),
            VersionTrigger.MANUAL_EDIT,
            traceId,
            now));

    log.info(
        "recipe manualEdit recipeId={} branchId={} newVersionId={} versionNumber={} userId={}",
        savedRecipe.getId(),
        savedBranch.getId(),
        savedNewVersion.getId(),
        savedNewVersion.getVersionNumber(),
        actorUserId);

    RecipeVersionDto versionDto = versionMapper.toDto(savedNewVersion);
    List<RecipeBranchDto> branches =
        branchMapper.toDtoList(branchRepository.findAllByRecipeId(savedRecipe.getId()));
    return recipeMapper.toDto(savedRecipe, versionDto, branches);
  }

  @Override
  @Transactional
  public RecipeBranchDto createBranch(
      UUID recipeId, CreateBranchRequest request, UUID actorUserId) {
    Recipe recipe = loadOwnedUserCatalogueRecipe(recipeId, actorUserId, "Branch creation");

    if (MAIN_BRANCH_NAME.equals(request.name())) {
      throw new RecipeBranchNameReservedException(request.name());
    }
    if (branchRepository.findByRecipeIdAndName(recipeId, request.name()).isPresent()) {
      throw new RecipeBranchNameConflictException(request.name());
    }

    RecipeVersion branchPoint =
        versionRepository
            .findById(request.branchPointVersionId())
            .filter(v -> v.getRecipe() != null && v.getRecipe().getId().equals(recipeId))
            .orElseThrow(
                () -> new RecipeBranchPointInvalidException(request.branchPointVersionId()));
    touchLazyChildren(branchPoint);

    NewVersionInput requested =
        new NewVersionInput(
            request.body().ingredients(),
            request.body().method(),
            request.body().metadata(),
            request.body().tags());

    CharacterFingerprintDto childFingerprint =
        request.fingerprintOverride() != null
            ? request.fingerprintOverride()
            : fingerprintDeriver.deriveFromBody(requested);
    CharacterFingerprintDto parentFingerprint =
        jsonToFingerprint(branchPoint.getCharacterFingerprint())
            .orElseGet(() -> fingerprintDeriver.deriveFromVersion(branchPoint));
    BigDecimal divergence = divergenceCalculator.compute(parentFingerprint, childFingerprint);

    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    String createdByActor = "user:" + actorUserId;
    UUID branchId = UUID.randomUUID();

    RecipeBranch newBranch =
        RecipeBranch.builder()
            .id(branchId)
            .recipe(recipe)
            .parentBranchId(
                branchPoint.getBranch() != null ? branchPoint.getBranch().getId() : null)
            .branchPointVersionId(branchPoint.getId())
            .name(request.name())
            .label(request.label())
            .reason(request.reason())
            .currentVersion(1)
            .divergenceScore(divergence)
            .createdByActor(createdByActor)
            .adapterTraceId(null)
            .build();
    RecipeBranch savedBranch = branchRepository.saveAndFlush(newBranch);

    ObjectNode changeDiff = versionDiffer.diff(branchPoint, requested);

    UUID newVersionId = UUID.randomUUID();
    RecipeVersion v1 =
        RecipeVersion.builder()
            .id(newVersionId)
            .recipe(recipe)
            .branch(savedBranch)
            .versionNumber(1)
            // 01d divergence note: cross-branch v1 keeps the FK to the branch-point version so the
            // genealogy survives; LLD line 108's "null for v1" applies only to main's v1 (which has
            // no parent).
            .parentVersionId(branchPoint.getId())
            .changeDiff(changeDiff)
            .changeReason(request.reason())
            .trigger(VersionTrigger.BRANCH_CREATION)
            .characterFingerprint(fingerprintToJson(childFingerprint))
            .nutritionPerServing(null)
            .embeddingStatus(EMBEDDING_STATUS_PENDING)
            .createdByActor(createdByActor)
            .adapterTraceId(null)
            .ingredients(new ArrayList<>())
            .methodSteps(new ArrayList<>())
            .build();

    populateIngredientsFromRequests(v1, request.body().ingredients());
    populateMethodStepsFromRequests(v1, request.body().method());
    v1.setMetadata(buildMetadataFromRequest(v1, request.body().metadata()));
    v1.setTags(buildTagsFromRequest(v1, request.body().tags()));

    RecipeVersion savedV1 = versionRepository.saveAndFlush(v1);

    // LLD invariant: do NOT mutate Recipe.currentBranchId / currentVersion — branch creation does
    // not switch the recipe's current branch. Checkout is a separate flow (recipe-01g).

    eventPublisher.publishEvent(
        new RecipeVersionCreatedEvent(
            savedV1.getId(),
            recipe.getId(),
            savedBranch.getId(),
            savedV1.getVersionNumber(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeBranchCreatedEvent(
            recipe.getId(),
            savedBranch.getId(),
            savedBranch.getParentBranchId(),
            savedBranch.getBranchPointVersionId(),
            savedBranch.getDivergenceScore(),
            traceId,
            now));

    log.info(
        "recipe createBranch recipeId={} branchId={} name={} divergence={} userId={}",
        recipe.getId(),
        savedBranch.getId(),
        savedBranch.getName(),
        savedBranch.getDivergenceScore(),
        actorUserId);

    return branchMapper.toDto(savedBranch);
  }

  @Override
  @Transactional
  public RecipeVersionDto revertToVersion(
      UUID recipeId,
      UUID branchId,
      int versionNumber,
      UUID actorUserId,
      long expectedRecipeOptimisticVersion) {
    Recipe recipe = loadOwnedUserCatalogueRecipe(recipeId, actorUserId, "Revert");
    if (recipe.getOptimisticVersion() != expectedRecipeOptimisticVersion) {
      throw new OptimisticLockingFailureException(
          "Stale expectedRecipeOptimisticVersion for recipe " + recipeId);
    }

    RecipeBranch branch =
        branchRepository
            .findById(branchId)
            .filter(b -> b.getRecipe() != null && b.getRecipe().getId().equals(recipeId))
            .orElseThrow(() -> new RecipeBranchNotFoundException(branchId));

    RecipeVersion target =
        versionRepository
            .findFirstByRecipeIdAndBranchIdAndVersionNumber(recipeId, branchId, versionNumber)
            .orElseThrow(() -> new RecipeVersionNotFoundException(null));
    touchLazyChildren(target);

    if (versionNumber == branch.getCurrentVersion()) {
      throw new NoChangesException("Target version is already the branch's current version.");
    }

    RecipeVersion current =
        versionRepository
            .findFirstByRecipeIdAndBranchIdAndVersionNumber(
                recipeId, branchId, branch.getCurrentVersion())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Recipe inconsistent: current version missing for branch " + branchId));
    touchLazyChildren(current);

    NewVersionInput targetAsInput = toNewVersionInput(target);
    ObjectNode changeDiff = versionDiffer.diff(current, targetAsInput);

    int newVersionNumber = branch.getCurrentVersion() + 1;
    UUID newVersionId = UUID.randomUUID();
    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    String createdByActor = "user:" + actorUserId;

    RecipeVersion newVersion =
        RecipeVersion.builder()
            .id(newVersionId)
            .recipe(recipe)
            .branch(branch)
            .versionNumber(newVersionNumber)
            .parentVersionId(current.getId())
            .changeDiff(changeDiff)
            .changeReason("Reverted to version " + target.getVersionNumber())
            .trigger(VersionTrigger.REVERT)
            // Fingerprint refreshes only on branch creation per LLD line 113 — keep the current
            // version's fingerprint (revert doesn't shift the branch's character).
            .characterFingerprint(current.getCharacterFingerprint())
            .nutritionPerServing(null)
            .embeddingStatus(EMBEDDING_STATUS_PENDING)
            .createdByActor(createdByActor)
            .adapterTraceId(null)
            .ingredients(new ArrayList<>())
            .methodSteps(new ArrayList<>())
            .build();

    populateIngredientsFromRequests(newVersion, targetAsInput.ingredients());
    populateMethodStepsFromRequests(newVersion, targetAsInput.method());
    newVersion.setMetadata(buildMetadataFromRequest(newVersion, targetAsInput.metadata()));
    newVersion.setTags(buildTagsFromRequest(newVersion, targetAsInput.tags()));

    RecipeVersion savedNewVersion = versionRepository.saveAndFlush(newVersion);

    branch.setCurrentVersion(newVersionNumber);
    RecipeBranch savedBranch = branchRepository.saveAndFlush(branch);

    // Bump Recipe.currentVersion only when reverting on the recipe's current branch (the LLD
    // distinguishes branch-current-version from recipe-current-version; only the recipe pointer
    // moves on the current branch).
    if (branchId.equals(recipe.getCurrentBranchId())) {
      recipe.setCurrentVersion(newVersionNumber);
    }
    Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);

    eventPublisher.publishEvent(
        new RecipeVersionCreatedEvent(
            savedNewVersion.getId(),
            savedRecipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getVersionNumber(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeUpdatedEvent(
            savedRecipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getId(),
            savedNewVersion.getVersionNumber(),
            VersionTrigger.REVERT,
            traceId,
            now));

    log.info(
        "recipe revert recipeId={} branchId={} targetVersionNumber={} newVersionNumber={} userId={}",
        savedRecipe.getId(),
        savedBranch.getId(),
        target.getVersionNumber(),
        savedNewVersion.getVersionNumber(),
        actorUserId);

    return versionMapper.toDto(savedNewVersion);
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

    populateIngredientsFromRequests(version, request.ingredients());
    populateMethodStepsFromRequests(version, request.method());
    version.setMetadata(buildMetadataFromRequest(version, request.metadata()));
    version.setTags(buildTagsFromRequest(version, request.tags()));

    RecipeVersion savedVersion = versionRepository.saveAndFlush(version);
    Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);

    eventPublisher.publishEvent(
        new RecipeCreatedEvent(
            savedRecipe.getId(),
            savedRecipe.getCatalogue(),
            savedRecipe.getUserId(),
            savedRecipe.getDataQuality(),
            traceId,
            now));
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

  // ---------------- Discovery import (discovery-01g) ----------------

  /**
   * Persist a recipe scraped by the discovery pipeline. Per ticket discovery-01g:
   *
   * <ol>
   *   <li>Dedup probe on {@code recipe_imports.content_fingerprint} — match → return existing.
   *   <li>Create {@code Recipe} with {@code catalogue=SYSTEM, data_quality=WEB_DISCOVERED}.
   *   <li>Create the 'main' branch (currentVersion=1, divergence=0).
   *   <li>Create {@code RecipeVersion v1} with {@code trigger=IMPORT} carrying the discovery trace.
   *   <li>Persist ingredients + method + metadata + tags.
   *   <li>Insert the {@code recipe_imports} row capturing source/job/fingerprint provenance.
   *   <li>Publish {@code RecipeCreatedEvent} + {@code RecipeVersionCreatedEvent}.
   * </ol>
   */
  @Override
  @Transactional
  public ImportedRecipeResult saveImportedRecipe(ImportedRecipeData data) {
    if (data == null) {
      throw new RecipeImportFailedException("ImportedRecipeData must not be null");
    }
    if (data.contentFingerprint() == null || data.contentFingerprint().isBlank()) {
      throw new RecipeImportFailedException("contentFingerprint is required");
    }
    if (data.name() == null || data.name().isBlank()) {
      throw new RecipeImportFailedException("name is required");
    }

    // Step 1: dedup probe.
    Optional<RecipeImport> existing =
        importRepository.findByContentFingerprint(data.contentFingerprint());
    if (existing.isPresent()) {
      RecipeImport row = existing.get();
      UUID existingRecipeId = row.getRecipeId();
      UUID existingVersionId =
          recipeRepository
              .findByIdAndDeletedAtIsNull(existingRecipeId)
              .flatMap(
                  r ->
                      r.getCurrentBranchId() == null
                          ? Optional.<RecipeVersion>empty()
                          : versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(
                              r.getId(), r.getCurrentBranchId(), r.getCurrentVersion()))
              .map(RecipeVersion::getId)
              .orElse(null);
      log.info(
          "discovery import dedup hit fingerprint={} existingRecipeId={} jobId={}",
          data.contentFingerprint(),
          existingRecipeId,
          data.jobId());
      return new ImportedRecipeResult(
          existingRecipeId,
          existingVersionId,
          false,
          "fingerprint-match-with-recipe-" + existingRecipeId);
    }

    try {
      return persistImportedRecipe(data);
    } catch (RecipeImportFailedException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new RecipeImportFailedException(
          "Failed to persist imported recipe: " + ex.getMessage(), ex);
    }
  }

  private ImportedRecipeResult persistImportedRecipe(ImportedRecipeData data) {
    Instant now = Instant.now(clock);
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID jobId = data.jobId();
    UUID traceId = data.traceId();
    String createdByActor = "system-discovery:" + jobId;

    // Step 2: recipe root.
    Recipe recipe =
        Recipe.builder()
            .id(recipeId)
            .userId(SYSTEM_DISCOVERY_USER_ID)
            .catalogue(Catalogue.SYSTEM)
            .name(data.name())
            .description(data.description())
            .currentVersion(1)
            .currentBranchId(null)
            .dataQuality(DataQuality.WEB_DISCOVERED)
            .nutritionStatus(NutritionStatus.PENDING)
            .build();
    recipe = recipeRepository.save(recipe);

    // Step 3: main branch.
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
            .adapterTraceId(jobId)
            .build();
    branch = branchRepository.save(branch);
    recipe.setCurrentBranchId(branch.getId());

    // Step 4: v1 version.
    RecipeVersion version =
        RecipeVersion.builder()
            .id(versionId)
            .recipe(recipe)
            .branch(branch)
            .versionNumber(1)
            .parentVersionId(null)
            .changeDiff(JsonNodeFactory.instance.objectNode())
            .changeReason(null)
            .trigger(VersionTrigger.IMPORT)
            .characterFingerprint(null)
            .nutritionPerServing(null)
            .embeddingStatus(EMBEDDING_STATUS_PENDING)
            .createdByActor(createdByActor)
            .adapterTraceId(traceId)
            .ingredients(new ArrayList<>())
            .methodSteps(new ArrayList<>())
            .build();

    // Step 5: children.
    populateImportedIngredients(version, data.ingredients());
    populateImportedMethodSteps(version, data.method());
    version.setMetadata(buildImportedMetadata(version, data.metadata()));
    version.setTags(buildImportedTags(version, data.tags()));

    RecipeVersion savedVersion = versionRepository.saveAndFlush(version);
    Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);

    // Step 6: provenance row.
    RecipeImport provenance =
        RecipeImport.builder()
            .id(UUID.randomUUID())
            .recipeId(savedRecipe.getId())
            .sourceType(ImportSource.WEB_DISCOVERED)
            .sourceUrl(data.canonicalUrl())
            .sourcePayload(null)
            .extractionMethod(data.extractionMethod())
            .duplicateOfRecipeId(null)
            .importedAt(now)
            .importedByUserId(SYSTEM_DISCOVERY_USER_ID)
            .contentFingerprint(data.contentFingerprint())
            .sourceKey(data.sourceKey())
            .canonicalUrl(data.canonicalUrl())
            .extractionConfidence(data.extractionConfidence())
            .jobId(jobId)
            .build();
    importRepository.save(provenance);

    // Step 7: events.
    eventPublisher.publishEvent(
        new RecipeCreatedEvent(
            savedRecipe.getId(),
            savedRecipe.getCatalogue(),
            savedRecipe.getUserId(),
            savedRecipe.getDataQuality(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeVersionCreatedEvent(
            savedVersion.getId(),
            savedRecipe.getId(),
            branch.getId(),
            savedVersion.getVersionNumber(),
            traceId,
            now));

    log.info(
        "discovery import persisted recipeId={} versionId={} sourceKey={} jobId={} fingerprint={}",
        savedRecipe.getId(),
        savedVersion.getId(),
        data.sourceKey(),
        jobId,
        data.contentFingerprint());

    return new ImportedRecipeResult(savedRecipe.getId(), savedVersion.getId(), true, null);
  }

  private static void populateImportedIngredients(
      RecipeVersion version, List<ImportedRecipeData.ImportedIngredient> ingredients) {
    if (ingredients == null) {
      return;
    }
    for (ImportedRecipeData.ImportedIngredient i : ingredients) {
      RecipeIngredient ingredient =
          RecipeIngredient.builder()
              .id(UUID.randomUUID())
              .version(version)
              .lineOrder(i.lineOrder())
              .ingredientMappingKey(null)
              .displayName(i.displayName())
              .quantity(i.quantity())
              .unit(i.unit())
              .preparation(i.preparation())
              .optional(i.optional())
              .needsReview(false)
              .mappingConfidence(null)
              .build();
      version.getIngredients().add(ingredient);
    }
  }

  private static void populateImportedMethodSteps(
      RecipeVersion version, List<ImportedRecipeData.ImportedMethodStep> steps) {
    if (steps == null) {
      return;
    }
    for (ImportedRecipeData.ImportedMethodStep s : steps) {
      RecipeMethodStep step =
          RecipeMethodStep.builder()
              .id(UUID.randomUUID())
              .version(version)
              .stepNumber(s.stepNumber())
              .instruction(s.instruction())
              .durationMinutes(s.durationMinutes())
              .build();
      version.getMethodSteps().add(step);
    }
  }

  private static RecipeMetadata buildImportedMetadata(
      RecipeVersion version, ImportedRecipeData.ImportedRecipeMetadata metadata) {
    if (metadata == null) {
      return RecipeMetadata.builder()
          .id(UUID.randomUUID())
          .version(version)
          .servings(0)
          .prepTimeMins(0)
          .cookTimeMins(0)
          .totalTimeMins(0)
          .equipmentRequired(new ArrayList<>())
          .fridgeDays(null)
          .freezerWeeks(null)
          .packable(false)
          .cuisine(null)
          .mealTypes(new ArrayList<>())
          .build();
    }
    return RecipeMetadata.builder()
        .id(UUID.randomUUID())
        .version(version)
        .servings(metadata.servings() != null ? metadata.servings() : 0)
        .prepTimeMins(metadata.prepTimeMins() != null ? metadata.prepTimeMins() : 0)
        .cookTimeMins(metadata.cookTimeMins() != null ? metadata.cookTimeMins() : 0)
        .totalTimeMins(metadata.totalTimeMins() != null ? metadata.totalTimeMins() : 0)
        .equipmentRequired(
            metadata.equipmentRequired() != null
                ? new ArrayList<>(metadata.equipmentRequired())
                : new ArrayList<>())
        .fridgeDays(metadata.fridgeDays())
        .freezerWeeks(metadata.freezerWeeks())
        .packable(metadata.packable() != null && metadata.packable())
        .cuisine(metadata.cuisine())
        .mealTypes(
            metadata.mealTypes() != null
                ? new ArrayList<>(metadata.mealTypes())
                : new ArrayList<>())
        .build();
  }

  private static RecipeTags buildImportedTags(
      RecipeVersion version, ImportedRecipeData.ImportedRecipeTags tags) {
    if (tags == null) {
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
        .protein(tags.protein())
        .cookingMethod(tags.cookingMethod())
        .complexity(parseComplexityOrNull(tags.complexity()))
        .flavourProfile(
            tags.flavourProfile() != null
                ? new ArrayList<>(tags.flavourProfile())
                : new ArrayList<>())
        .dietaryFlags(
            tags.dietaryFlags() != null ? new ArrayList<>(tags.dietaryFlags()) : new ArrayList<>())
        .build();
  }

  private static com.example.mealprep.recipe.domain.entity.Complexity parseComplexityOrNull(
      String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return com.example.mealprep.recipe.domain.entity.Complexity.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  // ---------------- Helpers ----------------

  /**
   * Load a recipe and gate write-side authorisation: recipe must exist + not be soft-deleted, must
   * be USER-catalogue, and the caller must own it. {@code operation} is used in the 422 message
   * (e.g. "Manual edit", "Branch creation"). Throws {@code RecipeNotFoundException} for "missing or
   * not yours" (don't leak existence) and {@code RecipeCatalogueViolationException} for SYSTEM.
   */
  private Recipe loadOwnedUserCatalogueRecipe(UUID recipeId, UUID actorUserId, String operation) {
    Recipe recipe =
        recipeRepository
            .findByIdAndDeletedAtIsNull(recipeId)
            .orElseThrow(() -> new RecipeNotFoundException(recipeId));
    if (recipe.getCatalogue() == Catalogue.SYSTEM) {
      throw new RecipeCatalogueViolationException(
          operation + " is not allowed on a SYSTEM-catalogue recipe; promote to USER first.");
    }
    if (!recipe.getUserId().equals(actorUserId)) {
      throw new RecipeNotFoundException(recipeId);
    }
    return recipe;
  }

  private static void touchLazyChildren(RecipeVersion version) {
    if (version == null) {
      return;
    }
    if (version.getIngredients() != null) {
      version.getIngredients().size();
    }
    if (version.getMethodSteps() != null) {
      version.getMethodSteps().size();
    }
    RecipeMetadata metadata = version.getMetadata();
    if (metadata != null) {
      if (metadata.getEquipmentRequired() != null) {
        metadata.getEquipmentRequired().size();
      }
      if (metadata.getMealTypes() != null) {
        metadata.getMealTypes().size();
      }
    }
    RecipeTags tags = version.getTags();
    if (tags != null) {
      if (tags.getFlavourProfile() != null) {
        tags.getFlavourProfile().size();
      }
      if (tags.getDietaryFlags() != null) {
        tags.getDietaryFlags().size();
      }
    }
  }

  private static void populateIngredientsFromRequests(
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

  private static void populateMethodStepsFromRequests(
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

  private static RecipeMetadata buildMetadataFromRequest(
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

  private static RecipeTags buildTagsFromRequest(
      RecipeVersion version, CreateRecipeTagsRequest request) {
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

  /**
   * Convert a persisted {@link RecipeVersion}'s body to a {@link NewVersionInput} carrier so the
   * revert flow can reuse {@link VersionDiffer} + the request-shaped child-row builders. Caller is
   * responsible for forcing lazy-load (see {@link #touchLazyChildren}).
   */
  private static NewVersionInput toNewVersionInput(RecipeVersion version) {
    List<CreateIngredientRequest> ingredients = new ArrayList<>();
    if (version.getIngredients() != null) {
      for (RecipeIngredient i : version.getIngredients()) {
        ingredients.add(
            new CreateIngredientRequest(
                i.getLineOrder(),
                i.getIngredientMappingKey(),
                i.getDisplayName(),
                i.getQuantity(),
                i.getUnit(),
                i.getPreparation(),
                i.isOptional()));
      }
    }
    List<CreateMethodStepRequest> method = new ArrayList<>();
    if (version.getMethodSteps() != null) {
      for (RecipeMethodStep s : version.getMethodSteps()) {
        method.add(
            new CreateMethodStepRequest(
                s.getStepNumber(), s.getInstruction(), s.getDurationMinutes()));
      }
    }
    RecipeMetadata md = version.getMetadata();
    CreateRecipeMetadataRequest metadata =
        md == null
            ? null
            : new CreateRecipeMetadataRequest(
                md.getServings(),
                md.getPrepTimeMins(),
                md.getCookTimeMins(),
                md.getTotalTimeMins(),
                md.getEquipmentRequired() != null
                    ? new ArrayList<>(md.getEquipmentRequired())
                    : new ArrayList<>(),
                md.getFridgeDays(),
                md.getFreezerWeeks(),
                md.isPackable(),
                md.getCuisine(),
                md.getMealTypes() != null ? new ArrayList<>(md.getMealTypes()) : new ArrayList<>());
    RecipeTags tg = version.getTags();
    CreateRecipeTagsRequest tags =
        tg == null
            ? null
            : new CreateRecipeTagsRequest(
                tg.getProtein(),
                tg.getCookingMethod(),
                tg.getComplexity(),
                tg.getFlavourProfile() != null
                    ? new ArrayList<>(tg.getFlavourProfile())
                    : new ArrayList<>(),
                tg.getDietaryFlags() != null
                    ? new ArrayList<>(tg.getDietaryFlags())
                    : new ArrayList<>());
    return new NewVersionInput(ingredients, method, metadata, tags);
  }

  private JsonNode fingerprintToJson(CharacterFingerprintDto fingerprint) {
    if (fingerprint == null) {
      return null;
    }
    return objectMapper.valueToTree(fingerprint);
  }

  private Optional<CharacterFingerprintDto> jsonToFingerprint(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(objectMapper.treeToValue(node, CharacterFingerprintDto.class));
    } catch (Exception ex) {
      log.warn("character_fingerprint JSON deserialization failed: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  // ---------------- Substitution: Query ----------------

  @Override
  @Transactional(readOnly = true)
  public List<RecipeSubstitutionDto> getActiveSubstitutions(UUID recipeId) {
    Recipe recipe =
        recipeRepository
            .findByIdAndDeletedAtIsNull(recipeId)
            .orElseThrow(() -> new RecipeNotFoundException(recipeId));
    return substitutionMapper.toDtoList(
        substitutionRepository.findAllByRecipeIdAndStateOrderByLastAppliedAtDesc(
            recipe.getId(), SubstitutionState.ACCEPTED));
  }

  @Override
  @Transactional(readOnly = true)
  public List<RecipeSubstitutionDto> getSubstitutionsForVersion(UUID versionId) {
    return substitutionMapper.toDtoList(
        substitutionRepository.findAllByVersionIdAndStateOrderByLastAppliedAtDesc(
            versionId, SubstitutionState.ACCEPTED));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RecipeSubstitutionDto> getSubstitution(UUID substitutionId) {
    return substitutionRepository.findById(substitutionId).map(substitutionMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public RecipeVersionDto getVersionWithSubstitutions(UUID recipeId, UUID versionId) {
    Recipe recipe =
        recipeRepository
            .findByIdAndDeletedAtIsNull(recipeId)
            .orElseThrow(() -> new RecipeNotFoundException(recipeId));
    RecipeVersion version =
        versionRepository
            .findById(versionId)
            .filter(v -> v.getRecipe() != null && v.getRecipe().getId().equals(recipe.getId()))
            .orElseThrow(() -> new RecipeVersionNotFoundException(versionId));
    touchLazyChildren(version);
    List<RecipeSubstitution> activeSubs =
        substitutionRepository.findAllByVersionIdAndStateOrderByLastAppliedAtDesc(
            version.getId(), SubstitutionState.ACCEPTED);
    NewVersionInput baseBody = toNewVersionInput(version);
    NewVersionInput overlaid = overlayApplier.apply(baseBody, activeSubs);
    List<UUID> appliedIds = new ArrayList<>();
    for (RecipeSubstitution s : activeSubs) {
      appliedIds.add(s.getId());
    }
    return versionMapper.toOverlayDto(
        version, overlaid.ingredients(), overlaid.method(), appliedIds);
  }

  // ---------------- Substitution: Update ----------------

  @Override
  @Transactional
  public RecipeSubstitutionDto createSubstitution(
      UUID recipeId, CreateSubstitutionRequest request, UUID actorUserId) {
    Recipe recipe = loadOwnedUserCatalogueRecipe(recipeId, actorUserId, "Create substitution");
    RecipeVersion version =
        versionRepository
            .findById(request.versionId())
            .filter(v -> v.getRecipe() != null && v.getRecipe().getId().equals(recipe.getId()))
            .orElseThrow(() -> new RecipeVersionNotFoundException(request.versionId()));

    List<String> mappingKeys = ingredientRepository.findMappingKeysByVersionId(version.getId());
    if (!mappingKeys.contains(request.original().ingredientMappingKey())) {
      throw new SubstitutionOriginalNotInVersionException(
          request.original().ingredientMappingKey());
    }

    UUID branchId = version.getBranch() != null ? version.getBranch().getId() : null;
    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    UUID subId = UUID.randomUUID();
    String createdByActor = "user:" + actorUserId;

    List<MethodOverlayLine> overlay = null;
    if (request.methodOverlay() != null && !request.methodOverlay().isEmpty()) {
      overlay = new ArrayList<>(request.methodOverlay().size());
      for (MethodOverlayLineRequest ml : request.methodOverlay()) {
        overlay.add(new MethodOverlayLine(ml.step(), ml.instruction()));
      }
    }

    RecipeSubstitution sub =
        RecipeSubstitution.builder()
            .id(subId)
            .recipeId(recipe.getId())
            .versionId(version.getId())
            .branchId(branchId)
            .originalMappingKey(request.original().ingredientMappingKey())
            .originalQuantity(request.original().quantity())
            .originalUnit(request.original().unit())
            .substituteMappingKey(request.substitute().ingredientMappingKey())
            .substituteQuantity(request.substitute().quantity())
            .substituteUnit(request.substitute().unit())
            .reason(request.reason())
            .constraintRef(request.constraintRef())
            .methodOverlay(overlay)
            .notes(request.notes())
            .temporary(request.temporary())
            .appliedInPlanIds(new UUID[0])
            .applicationCount(0)
            .lastAppliedAt(null)
            .state(SubstitutionState.PROPOSED)
            .promotedToVersionId(null)
            .createdByActor(createdByActor)
            .adapterTraceId(null)
            .build();

    RecipeSubstitution saved = substitutionRepository.saveAndFlush(sub);

    eventPublisher.publishEvent(
        new RecipeSubstitutionCreatedEvent(
            saved.getId(),
            recipe.getId(),
            version.getId(),
            branchId,
            saved.getReason(),
            traceId,
            now));

    log.info(
        "recipe createSubstitution recipeId={} versionId={} substitutionId={} userId={}",
        recipe.getId(),
        version.getId(),
        saved.getId(),
        actorUserId);

    return substitutionMapper.toDto(saved);
  }

  @Override
  @Transactional
  public RecipeSubstitutionDto acceptSubstitution(
      UUID substitutionId, UUID actorUserId, long expectedVersion) {
    RecipeSubstitution sub = loadOwnedSubstitution(substitutionId, actorUserId);
    if (sub.getVersion() != expectedVersion) {
      throw new OptimisticLockingFailureException(
          "Stale expectedVersion for substitution " + substitutionId);
    }
    SubstitutionState previous = sub.getState();
    if (previous == SubstitutionState.SUPERSEDED) {
      throw new SubstitutionTerminalStateException(previous);
    }
    if (previous == SubstitutionState.ACCEPTED) {
      // no-op (no @Version bump, no event)
      return substitutionMapper.toDto(sub);
    }
    sub.setState(SubstitutionState.ACCEPTED);
    RecipeSubstitution saved = substitutionRepository.saveAndFlush(sub);
    eventPublisher.publishEvent(
        new RecipeSubstitutionStateChangedEvent(
            saved.getId(),
            saved.getRecipeId(),
            saved.getVersionId(),
            previous,
            SubstitutionState.ACCEPTED,
            currentTraceId(),
            Instant.now(clock)));
    log.info(
        "recipe acceptSubstitution substitutionId={} previousState={} userId={}",
        saved.getId(),
        previous,
        actorUserId);
    return substitutionMapper.toDto(saved);
  }

  @Override
  @Transactional
  public RecipeSubstitutionDto rejectSubstitution(
      UUID substitutionId, UUID actorUserId, long expectedVersion, String reason) {
    RecipeSubstitution sub = loadOwnedSubstitution(substitutionId, actorUserId);
    if (sub.getVersion() != expectedVersion) {
      throw new OptimisticLockingFailureException(
          "Stale expectedVersion for substitution " + substitutionId);
    }
    SubstitutionState previous = sub.getState();
    if (previous == SubstitutionState.SUPERSEDED) {
      throw new SubstitutionTerminalStateException(previous);
    }
    if (previous == SubstitutionState.REJECTED) {
      return substitutionMapper.toDto(sub);
    }
    if (reason != null && !reason.isBlank()) {
      log.info(
          "recipe rejectSubstitution substitutionId={} reason='{}' userId={}",
          sub.getId(),
          reason,
          actorUserId);
    }
    sub.setState(SubstitutionState.REJECTED);
    RecipeSubstitution saved = substitutionRepository.saveAndFlush(sub);
    eventPublisher.publishEvent(
        new RecipeSubstitutionStateChangedEvent(
            saved.getId(),
            saved.getRecipeId(),
            saved.getVersionId(),
            previous,
            SubstitutionState.REJECTED,
            currentTraceId(),
            Instant.now(clock)));
    return substitutionMapper.toDto(saved);
  }

  @Override
  @Transactional
  public RecipeVersionDto promoteSubstitutionToVersion(
      UUID substitutionId, UUID actorUserId, long expectedVersion, String changeReason) {
    RecipeSubstitution sub = loadOwnedSubstitution(substitutionId, actorUserId);
    if (sub.getVersion() != expectedVersion) {
      throw new OptimisticLockingFailureException(
          "Stale expectedVersion for substitution " + substitutionId);
    }
    if (sub.getState() == SubstitutionState.SUPERSEDED) {
      throw new SubstitutionTerminalStateException(sub.getState());
    }
    if (sub.getState() != SubstitutionState.ACCEPTED) {
      throw new SubstitutionPromotionPreconditionException(sub.getState());
    }

    Recipe recipe =
        loadOwnedUserCatalogueRecipe(sub.getRecipeId(), actorUserId, "Promote substitution");

    RecipeVersion baseVersion =
        versionRepository
            .findById(sub.getVersionId())
            .orElseThrow(() -> new RecipeVersionNotFoundException(sub.getVersionId()));
    touchLazyChildren(baseVersion);
    RecipeBranch branch =
        branchRepository
            .findById(sub.getBranchId())
            .orElseThrow(() -> new RecipeBranchNotFoundException(sub.getBranchId()));

    NewVersionInput baseBody = toNewVersionInput(baseVersion);
    NewVersionInput appliedBody = overlayApplier.apply(baseBody, List.of(sub));
    ObjectNode changeDiff = versionDiffer.diff(baseVersion, appliedBody);

    int newVersionNumber = branch.getCurrentVersion() + 1;
    UUID newVersionId = UUID.randomUUID();
    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    String createdByActor = "user:" + actorUserId;

    RecipeVersion newVersion =
        RecipeVersion.builder()
            .id(newVersionId)
            .recipe(recipe)
            .branch(branch)
            .versionNumber(newVersionNumber)
            .parentVersionId(baseVersion.getId())
            .changeDiff(changeDiff)
            .changeReason(changeReason)
            .trigger(VersionTrigger.SUBSTITUTION_PROMOTION)
            .characterFingerprint(baseVersion.getCharacterFingerprint())
            .nutritionPerServing(null)
            .embeddingStatus(EMBEDDING_STATUS_PENDING)
            .createdByActor(createdByActor)
            .adapterTraceId(null)
            .ingredients(new ArrayList<>())
            .methodSteps(new ArrayList<>())
            .build();

    populateIngredientsFromRequests(newVersion, appliedBody.ingredients());
    populateMethodStepsFromRequests(newVersion, appliedBody.method());
    newVersion.setMetadata(buildMetadataFromRequest(newVersion, appliedBody.metadata()));
    newVersion.setTags(buildTagsFromRequest(newVersion, appliedBody.tags()));

    RecipeVersion savedNewVersion = versionRepository.saveAndFlush(newVersion);

    branch.setCurrentVersion(newVersionNumber);
    RecipeBranch savedBranch = branchRepository.saveAndFlush(branch);
    if (sub.getBranchId().equals(recipe.getCurrentBranchId())) {
      recipe.setCurrentVersion(newVersionNumber);
      recipeRepository.saveAndFlush(recipe);
    }

    SubstitutionState previous = sub.getState();
    sub.setState(SubstitutionState.SUPERSEDED);
    sub.setPromotedToVersionId(savedNewVersion.getId());
    substitutionRepository.saveAndFlush(sub);

    eventPublisher.publishEvent(
        new RecipeVersionCreatedEvent(
            savedNewVersion.getId(),
            recipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getVersionNumber(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeUpdatedEvent(
            recipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getId(),
            savedNewVersion.getVersionNumber(),
            VersionTrigger.SUBSTITUTION_PROMOTION,
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeSubstitutionStateChangedEvent(
            sub.getId(),
            sub.getRecipeId(),
            sub.getVersionId(),
            previous,
            SubstitutionState.SUPERSEDED,
            traceId,
            now));

    log.info(
        "recipe promoteSubstitution substitutionId={} newVersionId={} versionNumber={} userId={}",
        sub.getId(),
        savedNewVersion.getId(),
        savedNewVersion.getVersionNumber(),
        actorUserId);

    return versionMapper.toDto(savedNewVersion);
  }

  // ---------------- RecipeWriteApi SPI ----------------

  @Override
  @Transactional
  public RecipeVersionDto saveAdaptedVersion(SaveAdaptedVersionCommand cmd) {
    Recipe recipe =
        recipeRepository
            .findByIdForUpdate(cmd.recipeId())
            .orElseThrow(() -> new RecipeNotFoundException(cmd.recipeId()));
    RecipeBranch branch =
        branchRepository
            .findById(cmd.branchId())
            .filter(b -> b.getRecipe() != null && b.getRecipe().getId().equals(recipe.getId()))
            .orElseThrow(() -> new RecipeBranchNotFoundException(cmd.branchId()));

    if (recipe.getCurrentVersion() != cmd.expectedParentVersionNumber()) {
      throw new RecipeVersionConflictException(
          "Recipe "
              + recipe.getId()
              + " expected parent version "
              + cmd.expectedParentVersionNumber()
              + " but head is at "
              + recipe.getCurrentVersion());
    }
    UUID actualHeadVersionId =
        versionRepository
            .findCurrentVersionId(recipe.getId(), branch.getId(), branch.getCurrentVersion())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Recipe inconsistent: no current version row for branch "
                            + branch.getId()));
    if (!actualHeadVersionId.equals(cmd.expectedParentVersionId())) {
      throw new RecipeVersionConflictException(
          "Recipe "
              + recipe.getId()
              + " expected parent version id "
              + cmd.expectedParentVersionId()
              + " but head version id is "
              + actualHeadVersionId);
    }

    int newVersionNumber = recipe.getCurrentVersion() + 1;
    UUID newVersionId = UUID.randomUUID();
    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    String createdByActor =
        cmd.adapterTraceId() != null ? "pipeline:" + cmd.adapterTraceId() : "pipeline:unknown";

    RecipeVersion newVersion =
        RecipeVersion.builder()
            .id(newVersionId)
            .recipe(recipe)
            .branch(branch)
            .versionNumber(newVersionNumber)
            .parentVersionId(cmd.expectedParentVersionId())
            .changeDiff(
                cmd.changeDiff() != null ? cmd.changeDiff() : JsonNodeFactory.instance.objectNode())
            .changeReason(cmd.changeReason())
            .trigger(VersionTrigger.ADAPTATION_PIPELINE)
            .characterFingerprint(fingerprintToJson(cmd.characterFingerprint()))
            .nutritionPerServing(null)
            .embeddingStatus(EMBEDDING_STATUS_PENDING)
            .createdByActor(createdByActor)
            .adapterTraceId(cmd.adapterTraceId())
            .ingredients(new ArrayList<>())
            .methodSteps(new ArrayList<>())
            .build();

    populateIngredientsFromRequests(newVersion, cmd.ingredients());
    populateMethodStepsFromRequests(newVersion, cmd.method());
    newVersion.setMetadata(buildMetadataFromRequest(newVersion, cmd.metadata()));
    newVersion.setTags(buildTagsFromRequest(newVersion, cmd.tags()));

    RecipeVersion savedNewVersion = versionRepository.saveAndFlush(newVersion);

    recipe.setCurrentVersion(newVersionNumber);
    // Nutrition status resets to PENDING — the nutrition listener will back-fill via
    // updateNutritionStatus.
    recipe.setNutritionStatus(NutritionStatus.PENDING);
    Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);

    branch.setCurrentVersion(newVersionNumber);
    RecipeBranch savedBranch = branchRepository.saveAndFlush(branch);

    eventPublisher.publishEvent(
        new RecipeVersionCreatedEvent(
            savedNewVersion.getId(),
            savedRecipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getVersionNumber(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeUpdatedEvent(
            savedRecipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getId(),
            savedNewVersion.getVersionNumber(),
            VersionTrigger.ADAPTATION_PIPELINE,
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeAdaptedEvent(
            savedRecipe.getId(),
            savedBranch.getId(),
            savedNewVersion.getId(),
            AdaptationOutcomeType.NEW_VERSION,
            cmd.adapterTraceId(),
            traceId,
            now));

    log.info(
        "recipe saveAdaptedVersion recipeId={} branchId={} newVersionId={} versionNumber={} adapterTraceId={}",
        savedRecipe.getId(),
        savedBranch.getId(),
        savedNewVersion.getId(),
        savedNewVersion.getVersionNumber(),
        cmd.adapterTraceId());

    return versionMapper.toDto(savedNewVersion);
  }

  @Override
  @Transactional
  public RecipeBranchDto saveAdaptedBranch(SaveAdaptedBranchCommand cmd) {
    Recipe recipe =
        recipeRepository
            .findByIdAndDeletedAtIsNull(cmd.recipeId())
            .orElseThrow(() -> new RecipeNotFoundException(cmd.recipeId()));

    if (branchRepository.findByRecipeIdAndName(recipe.getId(), cmd.name()).isPresent()) {
      throw new RecipeBranchNameConflictException(cmd.name());
    }

    RecipeVersion branchPoint =
        versionRepository
            .findById(cmd.branchPointVersionId())
            .filter(v -> v.getRecipe() != null && v.getRecipe().getId().equals(recipe.getId()))
            .orElseThrow(() -> new RecipeBranchPointInvalidException(cmd.branchPointVersionId()));

    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    String createdByActor =
        cmd.adapterTraceId() != null ? "pipeline:" + cmd.adapterTraceId() : "pipeline:unknown";
    UUID branchId = UUID.randomUUID();

    RecipeBranch newBranch =
        RecipeBranch.builder()
            .id(branchId)
            .recipe(recipe)
            .parentBranchId(cmd.parentBranchId())
            .branchPointVersionId(branchPoint.getId())
            .name(cmd.name())
            .label(cmd.label())
            .reason(cmd.reason())
            .currentVersion(1)
            .divergenceScore(new BigDecimal("0.000"))
            .createdByActor(createdByActor)
            .adapterTraceId(cmd.adapterTraceId())
            .build();
    RecipeBranch savedBranch = branchRepository.saveAndFlush(newBranch);

    UUID newVersionId = UUID.randomUUID();
    RecipeVersion v1 =
        RecipeVersion.builder()
            .id(newVersionId)
            .recipe(recipe)
            .branch(savedBranch)
            .versionNumber(1)
            .parentVersionId(branchPoint.getId())
            .changeDiff(JsonNodeFactory.instance.objectNode())
            .changeReason(cmd.reason())
            .trigger(VersionTrigger.ADAPTATION_PIPELINE)
            .characterFingerprint(fingerprintToJson(cmd.characterFingerprint()))
            .nutritionPerServing(null)
            .embeddingStatus(EMBEDDING_STATUS_PENDING)
            .createdByActor(createdByActor)
            .adapterTraceId(cmd.adapterTraceId())
            .ingredients(new ArrayList<>())
            .methodSteps(new ArrayList<>())
            .build();

    populateIngredientsFromRequests(v1, cmd.ingredients());
    populateMethodStepsFromRequests(v1, cmd.method());
    v1.setMetadata(buildMetadataFromRequest(v1, cmd.metadata()));
    v1.setTags(buildTagsFromRequest(v1, cmd.tags()));

    RecipeVersion savedV1 = versionRepository.saveAndFlush(v1);

    // Per LLD: branch creation does NOT bump recipe.currentVersion / currentBranchId.

    eventPublisher.publishEvent(
        new RecipeVersionCreatedEvent(
            savedV1.getId(),
            recipe.getId(),
            savedBranch.getId(),
            savedV1.getVersionNumber(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeBranchCreatedEvent(
            recipe.getId(),
            savedBranch.getId(),
            savedBranch.getParentBranchId(),
            savedBranch.getBranchPointVersionId(),
            savedBranch.getDivergenceScore(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeAdaptedEvent(
            recipe.getId(),
            savedBranch.getId(),
            savedV1.getId(),
            AdaptationOutcomeType.BRANCH,
            cmd.adapterTraceId(),
            traceId,
            now));

    log.info(
        "recipe saveAdaptedBranch recipeId={} branchId={} name={} adapterTraceId={}",
        recipe.getId(),
        savedBranch.getId(),
        savedBranch.getName(),
        cmd.adapterTraceId());

    return branchMapper.toDto(savedBranch);
  }

  @Override
  @Transactional
  public RecipeSubstitutionDto saveAdaptedSubstitution(SaveAdaptedSubstitutionCommand cmd) {
    Recipe recipe =
        recipeRepository
            .findByIdAndDeletedAtIsNull(cmd.recipeId())
            .orElseThrow(() -> new RecipeNotFoundException(cmd.recipeId()));
    RecipeVersion version =
        versionRepository
            .findById(cmd.versionId())
            .filter(v -> v.getRecipe() != null && v.getRecipe().getId().equals(recipe.getId()))
            .orElseThrow(() -> new RecipeVersionNotFoundException(cmd.versionId()));

    List<String> mappingKeys = ingredientRepository.findMappingKeysByVersionId(version.getId());
    if (!mappingKeys.contains(cmd.original().ingredientMappingKey())) {
      throw new SubstitutionOriginalNotInVersionException(cmd.original().ingredientMappingKey());
    }

    UUID branchId = version.getBranch() != null ? version.getBranch().getId() : null;
    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    UUID subId = UUID.randomUUID();
    String createdByActor =
        cmd.adapterTraceId() != null ? "pipeline:" + cmd.adapterTraceId() : "pipeline:unknown";

    List<MethodOverlayLine> overlay = null;
    if (cmd.methodOverlay() != null && !cmd.methodOverlay().isEmpty()) {
      overlay = new ArrayList<>(cmd.methodOverlay().size());
      for (MethodOverlayLineRequest ml : cmd.methodOverlay()) {
        overlay.add(new MethodOverlayLine(ml.step(), ml.instruction()));
      }
    }

    RecipeSubstitution sub =
        RecipeSubstitution.builder()
            .id(subId)
            .recipeId(recipe.getId())
            .versionId(version.getId())
            .branchId(branchId)
            .originalMappingKey(cmd.original().ingredientMappingKey())
            .originalQuantity(cmd.original().quantity())
            .originalUnit(cmd.original().unit())
            .substituteMappingKey(cmd.substitute().ingredientMappingKey())
            .substituteQuantity(cmd.substitute().quantity())
            .substituteUnit(cmd.substitute().unit())
            .reason(cmd.reason())
            .constraintRef(cmd.constraintRef())
            .methodOverlay(overlay)
            .notes(cmd.notes())
            .temporary(cmd.temporary())
            .appliedInPlanIds(new UUID[0])
            .applicationCount(0)
            .lastAppliedAt(null)
            .state(SubstitutionState.ACCEPTED)
            .promotedToVersionId(null)
            .createdByActor(createdByActor)
            .adapterTraceId(cmd.adapterTraceId())
            .build();

    RecipeSubstitution saved = substitutionRepository.saveAndFlush(sub);

    eventPublisher.publishEvent(
        new RecipeSubstitutionCreatedEvent(
            saved.getId(),
            recipe.getId(),
            version.getId(),
            branchId,
            saved.getReason(),
            traceId,
            now));
    eventPublisher.publishEvent(
        new RecipeAdaptedEvent(
            recipe.getId(),
            branchId,
            version.getId(),
            AdaptationOutcomeType.SUBSTITUTION,
            cmd.adapterTraceId(),
            traceId,
            now));

    log.info(
        "recipe saveAdaptedSubstitution recipeId={} versionId={} substitutionId={} adapterTraceId={}",
        recipe.getId(),
        version.getId(),
        saved.getId(),
        cmd.adapterTraceId());

    return substitutionMapper.toDto(saved);
  }

  @Override
  @Transactional
  public void updateNutritionStatus(
      UUID versionId, NutritionStatus status, JsonNode nutritionPerServing) {
    RecipeVersion version =
        versionRepository
            .findById(versionId)
            .orElseThrow(() -> new RecipeVersionNotFoundException(versionId));
    // Append-only exception per LLD line 130: nutrition recalculation is one of three permitted
    // mutations on a persisted version row.
    version.setNutritionPerServing(nutritionPerServing);
    versionRepository.saveAndFlush(version);

    // Aggregate-level status lives on Recipe per the recipe_recipes schema; the version's
    // nutrition_status column ships in a later schema rev. Update the recipe-level status now so
    // the read DTO reflects the recalculation outcome.
    Recipe recipe = version.getRecipe();
    if (recipe != null) {
      recipe.setNutritionStatus(status);
      recipeRepository.saveAndFlush(recipe);
    }

    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    eventPublisher.publishEvent(
        new RecipeEvolvedEvent(
            recipe != null ? recipe.getId() : null,
            versionId,
            EvolvedReason.NUTRITION_RECALCULATED,
            traceId,
            now));

    log.info(
        "recipe updateNutritionStatus versionId={} status={} hasJson={}",
        versionId,
        status,
        nutritionPerServing != null && !nutritionPerServing.isNull());
  }

  @Override
  @Transactional
  public void updateCharacterFingerprint(UUID versionId, CharacterFingerprintDto fingerprint) {
    RecipeVersion version =
        versionRepository
            .findById(versionId)
            .orElseThrow(() -> new RecipeVersionNotFoundException(versionId));
    version.setCharacterFingerprint(fingerprintToJson(fingerprint));
    versionRepository.saveAndFlush(version);

    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    UUID recipeId = version.getRecipe() != null ? version.getRecipe().getId() : null;
    eventPublisher.publishEvent(
        new RecipeEvolvedEvent(
            recipeId, versionId, EvolvedReason.FINGERPRINT_REFRESHED, traceId, now));

    log.info("recipe updateCharacterFingerprint versionId={}", versionId);
  }

  @Override
  @Transactional
  public void updateBranchDivergence(UUID branchId, BigDecimal divergenceScore) {
    RecipeBranch branch =
        branchRepository
            .findById(branchId)
            .orElseThrow(() -> new RecipeBranchNotFoundException(branchId));
    branch.setDivergenceScore(divergenceScore);
    branchRepository.saveAndFlush(branch);
    log.info("recipe updateBranchDivergence branchId={} score={}", branchId, divergenceScore);
  }

  @Override
  @Transactional
  public void storeEmbedding(UUID versionId, float[] embedding, String modelId) {
    // recipe-01h: native UPDATE bypasses Hibernate's full-entity dirty-check. The async listener
    // fires AFTER_COMMIT but the create flow's persistence context may still observe the row;
    // saveAndFlush of a re-loaded RecipeVersion (with cascaded child collections) triggered
    // StaleObjectStateException. The native UPDATE only touches the embedding columns and casts
    // the pgvector text literal at the SQL layer (see RecipeVersionRepository.updateEmbedding).
    Instant now = Instant.now(clock);
    String pgVectorText = formatPgVector(embedding);
    int rows = versionRepository.updateEmbedding(versionId, pgVectorText, modelId, now);
    if (rows == 0) {
      throw new RecipeVersionNotFoundException(versionId);
    }

    UUID traceId = currentTraceId();
    UUID recipeId =
        versionRepository
            .findById(versionId)
            .map(v -> v.getRecipe() != null ? v.getRecipe().getId() : null)
            .orElse(null);
    eventPublisher.publishEvent(
        new RecipeEvolvedEvent(recipeId, versionId, EvolvedReason.EMBEDDING_STORED, traceId, now));

    log.info(
        "recipe storeEmbedding versionId={} modelId={} vectorDim={}",
        versionId,
        modelId,
        embedding != null ? embedding.length : 0);
  }

  @Override
  @Transactional
  public void markEmbeddingFailed(UUID versionId) {
    // Native UPDATE for the same reason as storeEmbedding — only flip embedding_status.
    int rows = versionRepository.markEmbeddingFailed(versionId);
    if (rows == 0) {
      throw new RecipeVersionNotFoundException(versionId);
    }

    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    UUID recipeId =
        versionRepository
            .findById(versionId)
            .map(v -> v.getRecipe() != null ? v.getRecipe().getId() : null)
            .orElse(null);
    eventPublisher.publishEvent(
        new RecipeEvolvedEvent(recipeId, versionId, EvolvedReason.EMBEDDING_FAILED, traceId, now));

    log.info("recipe markEmbeddingFailed versionId={}", versionId);
  }

  /**
   * Render a {@code float[]} as the pgvector text literal {@code '[v1,v2,...,vN]'}. Mirrors {@link
   * com.example.mealprep.recipe.domain.entity.RecipeEmbeddingConverter} — kept here because the
   * native UPDATE bypasses the JPA AttributeConverter pipeline.
   */
  private static String formatPgVector(float[] v) {
    if (v == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(v.length * 8 + 2);
    sb.append('[');
    for (int i = 0; i < v.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Float.toString(v[i]));
    }
    sb.append(']');
    return sb.toString();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<float[]> getEmbedding(UUID versionId) {
    return versionRepository
        .findById(versionId)
        .map(RecipeVersion::getEmbedding)
        .filter(arr -> arr != null && arr.length > 0);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, List<String>> findUserRecipeIngredientKeys(UUID userId) {
    if (userId == null) {
      return Map.of();
    }
    // Single projection query (recipeId, ingredientMappingKey) over each active recipe's current
    // version; an inner join on ingredients means zero-ingredient recipes contribute no rows and
    // are naturally absent. Assemble row groups into a Map — preserve insertion order for stable
    // iteration. No N+1: one SELECT regardless of recipe count.
    List<Object[]> rows = recipeRepository.findCurrentVersionIngredientKeysForUser(userId);
    Map<UUID, List<String>> result = new LinkedHashMap<>();
    for (Object[] row : rows) {
      UUID recipeId = (UUID) row[0];
      String mappingKey = (String) row[1];
      result.computeIfAbsent(recipeId, k -> new ArrayList<>()).add(mappingKey);
    }
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, JsonNode> findUserRecipeNutrition(UUID userId) {
    if (userId == null) {
      return Map.of();
    }
    // Single projection query (recipeId, nutritionPerServing) over each active recipe's current
    // version, pruning null-nutrition versions in SQL. The jsonb is returned verbatim as a
    // JsonNode — the recipe module stores nutrition opaquely (written via the nutrition SPI) and
    // does NOT interpret its shape; that belongs to the nutrition module. No N+1: one SELECT.
    List<Object[]> rows = recipeRepository.findCurrentVersionNutritionForUser(userId);
    Map<UUID, JsonNode> result = new LinkedHashMap<>();
    for (Object[] row : rows) {
      result.put((UUID) row[0], (JsonNode) row[1]);
    }
    return result;
  }

  // ---------------- RecipeSubstitutionRecorder SPI ----------------

  @Override
  @Transactional
  public void recordSubstitution(UUID substitutionId, UUID planId) {
    RecipeSubstitution sub =
        substitutionRepository
            .findById(substitutionId)
            .orElseThrow(() -> new RecipeSubstitutionNotFoundException(substitutionId));
    if (sub.getState() != SubstitutionState.ACCEPTED) {
      throw new SubstitutionRecordPreconditionException(sub.getState());
    }
    UUID[] current = sub.getAppliedInPlanIds();
    if (current == null) {
      current = new UUID[0];
    }
    for (UUID existing : current) {
      if (existing != null && existing.equals(planId)) {
        // Idempotent: plan id already recorded.
        return;
      }
    }
    UUID[] next = new UUID[current.length + 1];
    System.arraycopy(current, 0, next, 0, current.length);
    next[current.length] = planId;
    sub.setAppliedInPlanIds(next);
    sub.setApplicationCount(sub.getApplicationCount() + 1);
    sub.setLastAppliedAt(Instant.now(clock));
    substitutionRepository.save(sub);
  }

  private RecipeSubstitution loadOwnedSubstitution(UUID substitutionId, UUID actorUserId) {
    RecipeSubstitution sub =
        substitutionRepository
            .findById(substitutionId)
            .orElseThrow(() -> new RecipeSubstitutionNotFoundException(substitutionId));
    Recipe recipe =
        recipeRepository
            .findByIdAndDeletedAtIsNull(sub.getRecipeId())
            .orElseThrow(() -> new RecipeSubstitutionNotFoundException(substitutionId));
    if (recipe.getCatalogue() == Catalogue.SYSTEM) {
      throw new RecipeCatalogueViolationException(
          "Substitution actions are not allowed on SYSTEM-catalogue recipes; promote to USER first.");
    }
    if (!recipe.getUserId().equals(actorUserId)) {
      throw new RecipeSubstitutionNotFoundException(substitutionId);
    }
    return sub;
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

  // ---------------- recipe-01g: Promote / Demote / Archive / Unarchive / markUsedInPlan
  // ----------------

  @Override
  @Transactional
  public RecipeDto promoteToUserCatalogue(UUID systemRecipeId, UUID userId) {
    Recipe recipe =
        recipeRepository
            .findById(systemRecipeId)
            .orElseThrow(() -> new RecipeNotFoundException(systemRecipeId));
    if (recipe.getCatalogue() != Catalogue.SYSTEM) {
      throw new RecipeCatalogueViolationException("recipe is already in user catalogue");
    }
    if (recipe.getDeletedAt() != null) {
      throw new RecipeCatalogueViolationException("recipe is deleted");
    }
    if (recipe.getArchivedAt() != null) {
      throw new RecipeCatalogueViolationException("recipe is archived; unarchive before promoting");
    }

    recipe.setCatalogue(Catalogue.USER);
    recipe.setUserId(userId);
    Recipe saved = recipeRepository.saveAndFlush(recipe);

    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    eventPublisher.publishEvent(
        new RecipePromotedEvent(
            saved.getId(), userId, Catalogue.SYSTEM, Catalogue.USER, traceId, now));

    log.info(
        "recipe promoteToUserCatalogue recipeId={} userId={} traceId={}",
        saved.getId(),
        userId,
        traceId);

    List<RecipeBranchDto> branches =
        branchMapper.toDtoList(branchRepository.findAllByRecipeId(saved.getId()));
    RecipeVersionDto versionDto = null;
    if (saved.getCurrentBranchId() != null) {
      Optional<RecipeVersion> versionOpt =
          versionRepository.findFirstByRecipeIdAndBranchIdAndVersionNumber(
              saved.getId(), saved.getCurrentBranchId(), saved.getCurrentVersion());
      if (versionOpt.isPresent()) {
        RecipeVersion version = versionOpt.get();
        touchLazyChildren(version);
        versionDto = versionMapper.toDto(version);
      }
    }
    return recipeMapper.toDto(saved, versionDto, branches);
  }

  @Override
  @Transactional
  public void demoteToSystemCatalogue(UUID userRecipeId, UUID actorUserId) {
    Recipe recipe =
        recipeRepository
            .findById(userRecipeId)
            .orElseThrow(() -> new RecipeNotFoundException(userRecipeId));
    // Ownership / existence: don't leak existence for someone else's recipe.
    if (recipe.getUserId() == null || !recipe.getUserId().equals(actorUserId)) {
      throw new RecipeNotFoundException(userRecipeId);
    }
    if (recipe.getCatalogue() != Catalogue.USER) {
      throw new RecipeCatalogueViolationException("recipe is already in system catalogue");
    }

    recipe.setCatalogue(Catalogue.SYSTEM);
    // userId is RETAINED for provenance per LLD line 764.
    recipeRepository.saveAndFlush(recipe);

    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    eventPublisher.publishEvent(
        new RecipeArchivedEvent(recipe.getId(), ArchiveCause.USER_DEMOTION, traceId, now));

    log.info(
        "recipe demoteToSystemCatalogue recipeId={} actorUserId={} traceId={}",
        recipe.getId(),
        actorUserId,
        traceId);
  }

  @Override
  @Transactional
  public void archive(UUID recipeId, UUID actorUserId) {
    Recipe recipe =
        recipeRepository
            .findById(recipeId)
            .orElseThrow(() -> new RecipeNotFoundException(recipeId));
    authoriseArchiveOp(recipe, actorUserId);
    if (recipe.getDeletedAt() != null) {
      throw new RecipeCatalogueViolationException("recipe is already deleted");
    }
    if (recipe.getArchivedAt() != null) {
      // Idempotent no-op — no new event.
      return;
    }
    recipe.setArchivedAt(Instant.now(clock));
    recipeRepository.saveAndFlush(recipe);

    UUID traceId = currentTraceId();
    Instant now = Instant.now(clock);
    eventPublisher.publishEvent(
        new RecipeArchivedEvent(recipe.getId(), ArchiveCause.MANUAL_ADMIN, traceId, now));

    log.info(
        "recipe archive recipeId={} actorUserId={} traceId={}",
        recipe.getId(),
        actorUserId,
        traceId);
  }

  @Override
  @Transactional
  public void unarchive(UUID recipeId, UUID actorUserId) {
    Recipe recipe =
        recipeRepository
            .findById(recipeId)
            .orElseThrow(() -> new RecipeNotFoundException(recipeId));
    authoriseArchiveOp(recipe, actorUserId);
    if (recipe.getArchivedAt() == null) {
      // Idempotent no-op — no event (LLD has no RecipeUnarchivedEvent).
      return;
    }
    recipe.setArchivedAt(null);
    recipeRepository.saveAndFlush(recipe);

    log.info("recipe unarchive recipeId={} actorUserId={}", recipe.getId(), actorUserId);
  }

  @Override
  @Transactional
  public void markUsedInPlan(List<UUID> recipeIds) {
    if (recipeIds == null || recipeIds.isEmpty()) {
      return;
    }
    int updated = recipeRepository.touchLastUsedInPlan(recipeIds, Instant.now(clock));
    log.info("recipe markUsedInPlan size={} updated={}", recipeIds.size(), updated);
  }

  /**
   * Authorisation gate for {@code archive} / {@code unarchive}: USER recipes require ownership;
   * SYSTEM recipes are open to any authenticated caller per recipe-01g §Archive (manual) (v1
   * admin-open policy; locks down when role enum gains {@code ADMIN}).
   */
  private void authoriseArchiveOp(Recipe recipe, UUID actorUserId) {
    if (recipe.getCatalogue() == Catalogue.USER) {
      if (recipe.getUserId() == null || !recipe.getUserId().equals(actorUserId)) {
        throw new RecipeNotFoundException(recipe.getId());
      }
    }
    // SYSTEM: open to any authenticated caller — see ticket §Archive (manual) line 92.
  }
}
