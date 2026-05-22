package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CreateRatingRequest;
import com.example.mealprep.recipe.api.dto.RecipeRatingDto;
import com.example.mealprep.recipe.api.dto.RecipeRatingSummaryDto;
import com.example.mealprep.recipe.api.dto.UpdateRatingRequest;
import com.example.mealprep.recipe.api.mapper.RecipeRatingMapper;
import com.example.mealprep.recipe.domain.entity.RecipeRating;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.repository.RecipeRatingRepository;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.domain.service.RecipeRatingQueryService;
import com.example.mealprep.recipe.domain.service.RecipeRatingUpdateService;
import com.example.mealprep.recipe.event.RecipeRatingFiredEvent;
import com.example.mealprep.recipe.exception.DuplicateRecipeRatingException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.exception.RecipeRatingNotFoundException;
import com.example.mealprep.recipe.exception.RecipeRatingValidationException;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of the recipe-02b rating services. Writes are {@code @Transactional} and
 * publish a {@link RecipeRatingFiredEvent} (consumed {@code AFTER_COMMIT} by the feedback module's
 * future listener) so the rating contributes to taste-profile learning — C-IMP-009.
 *
 * <p>The aggregate is a weighted blend computed server-side at write time. Weights (40/25/15/20)
 * are a recipe-02b proposal — not specified in the HLD — that favours taste. A missing non-taste
 * dimension coalesces to the taste value so single-tap ratings yield {@code aggregate == taste}.
 */
@Service
public class RecipeRatingServiceImpl
    implements RecipeRatingQueryService, RecipeRatingUpdateService {

  private static final Logger log = LoggerFactory.getLogger(RecipeRatingServiceImpl.class);
  private static final String MDC_TRACE_ID = "traceId";

  private static final double WEIGHT_TASTE = 0.40;
  private static final double WEIGHT_EFFORT = 0.25;
  private static final double WEIGHT_PORTION = 0.15;
  private static final double WEIGHT_REPEAT = 0.20;

  private final RecipeRatingRepository ratingRepository;
  private final RecipeVersionRepository versionRepository;
  private final RecipeRepository recipeRepository;
  private final RecipeRatingMapper ratingMapper;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public RecipeRatingServiceImpl(
      RecipeRatingRepository ratingRepository,
      RecipeVersionRepository versionRepository,
      RecipeRepository recipeRepository,
      RecipeRatingMapper ratingMapper,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.ratingRepository = ratingRepository;
    this.versionRepository = versionRepository;
    this.recipeRepository = recipeRepository;
    this.ratingMapper = ratingMapper;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<RecipeRatingDto> getById(UUID ratingId) {
    return ratingRepository.findById(ratingId).map(ratingMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RecipeRatingDto> getByVersionAndUser(UUID versionId, UUID userId) {
    return ratingRepository.findByVersionIdAndUserId(versionId, userId).map(ratingMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<RecipeRatingDto> listByVersion(UUID versionId, Pageable p) {
    return ratingRepository
        .findByVersionIdOrderByCreatedAtDesc(versionId, p)
        .map(ratingMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<RecipeRatingDto> listByRecipe(UUID recipeId, Pageable p) {
    return ratingRepository
        .findByRecipeIdOrderByCreatedAtDesc(recipeId, p)
        .map(ratingMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<RecipeRatingDto> listByUser(UUID userId, Pageable p) {
    return ratingRepository.findByUserIdOrderByCreatedAtDesc(userId, p).map(ratingMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public RecipeRatingSummaryDto getSummaryByVersion(UUID versionId) {
    RecipeRatingSummaryDto summary = ratingRepository.aggregateByVersion(versionId);
    // The grouped query returns null when no ratings exist; surface an empty summary.
    return summary != null
        ? summary
        : new RecipeRatingSummaryDto(versionId, null, null, null, null, null, 0L);
  }

  @Override
  @Transactional(readOnly = true)
  public RecipeRatingSummaryDto getSummaryByRecipe(UUID recipeId) {
    return ratingRepository.aggregateByRecipe(recipeId);
  }

  // ---------------- Write ----------------

  @Override
  @Transactional
  public RecipeRatingDto create(UUID userId, UUID recipeId, CreateRatingRequest request) {
    RecipeVersion version = loadVersionForRecipe(recipeId, request.versionId());

    ratingRepository
        .findByVersionIdAndUserId(version.getId(), userId)
        .ifPresent(
            existing -> {
              throw new DuplicateRecipeRatingException(version.getId());
            });

    UUID traceId = currentTraceId();
    int aggregate =
        computeAggregate(
            request.taste(), request.effortWorthIt(), request.portionFit(), request.repeatValue());

    RecipeRating rating =
        RecipeRating.builder()
            .id(UUID.randomUUID())
            .recipeId(recipeId)
            .versionId(version.getId())
            .userId(userId)
            .householdId(null)
            .slotId(request.slotId())
            .taste(request.taste())
            .effortWorthIt(request.effortWorthIt())
            .portionFit(request.portionFit())
            .repeatValue(request.repeatValue())
            .aggregate(aggregate)
            .notes(request.notes())
            .traceId(traceId)
            .build();

    RecipeRating saved = ratingRepository.saveAndFlush(rating);
    publishFired(saved, traceId);

    log.info(
        "recipe createRating recipeId={} versionId={} ratingId={} userId={} aggregate={}",
        recipeId,
        version.getId(),
        saved.getId(),
        userId,
        aggregate);

    return ratingMapper.toDto(saved);
  }

  @Override
  @Transactional
  public RecipeRatingDto update(
      UUID userId, UUID recipeId, UUID ratingId, UpdateRatingRequest request) {
    RecipeRating rating =
        ratingRepository
            .findByIdAndUserId(ratingId, userId)
            .orElseThrow(() -> new RecipeRatingNotFoundException(ratingId));

    // The version cannot move to a different recipe; validate the body against the path + the row.
    RecipeVersion version = loadVersionForRecipe(recipeId, request.versionId());
    if (!rating.getVersionId().equals(version.getId())) {
      throw new RecipeRatingValidationException(
          "versionId in body does not match the rating's version");
    }

    if (rating.getOptimisticVersion() != request.expectedVersion()) {
      throw new OptimisticLockingFailureException("Stale expectedVersion for rating " + ratingId);
    }

    int aggregate =
        computeAggregate(
            request.taste(), request.effortWorthIt(), request.portionFit(), request.repeatValue());

    rating.setTaste(request.taste());
    rating.setEffortWorthIt(request.effortWorthIt());
    rating.setPortionFit(request.portionFit());
    rating.setRepeatValue(request.repeatValue());
    rating.setAggregate(aggregate);
    rating.setNotes(request.notes());

    UUID traceId = currentTraceId();
    rating.setTraceId(traceId);

    RecipeRating saved = ratingRepository.saveAndFlush(rating);
    publishFired(saved, traceId);

    log.info(
        "recipe updateRating recipeId={} ratingId={} userId={} aggregate={} optimisticVersion={}",
        recipeId,
        saved.getId(),
        userId,
        aggregate,
        saved.getOptimisticVersion());

    return ratingMapper.toDto(saved);
  }

  @Override
  @Transactional
  public void delete(UUID userId, UUID recipeId, UUID ratingId) {
    RecipeRating rating =
        ratingRepository
            .findByIdAndUserId(ratingId, userId)
            .orElseThrow(() -> new RecipeRatingNotFoundException(ratingId));
    ratingRepository.delete(rating);
    log.info("recipe deleteRating recipeId={} ratingId={} userId={}", recipeId, ratingId, userId);
  }

  // ---------------- Internals ----------------

  /**
   * Compute the weighted aggregate. Missing non-taste dimensions coalesce to taste so a single-tap
   * rating yields its taste as the aggregate. Rounded half-up via {@link Math#round}.
   */
  public static int computeAggregate(
      int taste, Integer effortWorthIt, Integer portionFit, Integer repeatValue) {
    int effort = effortWorthIt != null ? effortWorthIt : taste;
    int portion = portionFit != null ? portionFit : taste;
    int repeat = repeatValue != null ? repeatValue : taste;
    double blended =
        (taste * WEIGHT_TASTE)
            + (effort * WEIGHT_EFFORT)
            + (portion * WEIGHT_PORTION)
            + (repeat * WEIGHT_REPEAT);
    return (int) Math.round(blended);
  }

  /**
   * Load the version named in the body and enforce that it belongs to the path's recipe. A missing
   * version is a 404; a version that resolves to a different recipe is a 400 validation error
   * (path-vs-body mismatch). The recipe itself must exist (404) before we trust the path.
   */
  private RecipeVersion loadVersionForRecipe(UUID recipeId, UUID versionId) {
    if (!recipeRepository.findByIdAndDeletedAtIsNull(recipeId).isPresent()) {
      throw new RecipeNotFoundException(recipeId);
    }
    RecipeVersion version =
        versionRepository
            .findById(versionId)
            .orElseThrow(() -> new RecipeVersionNotFoundException(versionId));
    UUID versionRecipeId = version.getRecipe() != null ? version.getRecipe().getId() : null;
    if (versionRecipeId == null || !versionRecipeId.equals(recipeId)) {
      throw new RecipeRatingValidationException(
          "versionId " + versionId + " does not belong to recipe " + recipeId);
    }
    return version;
  }

  private void publishFired(RecipeRating rating, UUID traceId) {
    eventPublisher.publishEvent(
        new RecipeRatingFiredEvent(
            rating.getId(),
            rating.getUserId(),
            rating.getRecipeId(),
            rating.getVersionId(),
            rating.getSlotId(),
            rating.getTaste(),
            rating.getEffortWorthIt(),
            rating.getPortionFit(),
            rating.getRepeatValue(),
            rating.getAggregate(),
            rating.getNotes(),
            traceId,
            Instant.now(clock)));
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
