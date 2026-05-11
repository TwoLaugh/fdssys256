package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateBranchRequest;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import com.example.mealprep.recipe.api.mapper.ParsedRecipeToCreateRequestMapper;
import com.example.mealprep.recipe.api.mapper.RecipeBranchMapper;
import com.example.mealprep.recipe.api.mapper.RecipeDiffMapper;
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
import com.example.mealprep.recipe.event.RecipeBranchCreatedEvent;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
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
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  private final RecipeDiffMapper diffMapper;
  private final UrlFetcher urlFetcher;
  private final HtmlImportParser htmlImportParser;
  private final ParsedRecipeToCreateRequestMapper parserToCreateRequestMapper;
  private final VersionDiffer versionDiffer;
  private final DivergenceScoreCalculator divergenceCalculator;
  private final FingerprintDeriver fingerprintDeriver;
  private final ObjectMapper objectMapper;
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
      RecipeDiffMapper diffMapper,
      UrlFetcher urlFetcher,
      HtmlImportParser htmlImportParser,
      ParsedRecipeToCreateRequestMapper parserToCreateRequestMapper,
      VersionDiffer versionDiffer,
      DivergenceScoreCalculator divergenceCalculator,
      FingerprintDeriver fingerprintDeriver,
      ObjectMapper objectMapper,
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
    this.diffMapper = diffMapper;
    this.urlFetcher = urlFetcher;
    this.htmlImportParser = htmlImportParser;
    this.parserToCreateRequestMapper = parserToCreateRequestMapper;
    this.versionDiffer = versionDiffer;
    this.divergenceCalculator = divergenceCalculator;
    this.fingerprintDeriver = fingerprintDeriver;
    this.objectMapper = objectMapper;
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
              touchLazyChildren(version);
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
