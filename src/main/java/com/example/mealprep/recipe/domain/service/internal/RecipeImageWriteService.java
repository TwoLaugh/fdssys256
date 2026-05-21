package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.event.RecipeImageUpdatedEvent;
import com.example.mealprep.recipe.exception.RecipeAccessDeniedException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import com.example.mealprep.recipe.spi.RecipeImageStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Recipe-02a transactional write path for image uploads. Centralises the owner-only authorisation,
 * the store delegation, the optimistic-locked update on the recipe row, and the {@code
 * AFTER_COMMIT} event emit. Lives in {@code domain.service.internal} alongside {@code
 * RecipeServiceImpl} so it can inject the package-private {@link RecipeRepository} and the entity
 * directly — controllers don't touch entities (enforced by ArchUnit).
 */
@Service
public class RecipeImageWriteService {

  private static final Logger log = LoggerFactory.getLogger(RecipeImageWriteService.class);

  private static final String MDC_TRACE_ID = "traceId";

  private final RecipeRepository recipeRepository;
  private final RecipeImageStore imageStore;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public RecipeImageWriteService(
      RecipeRepository recipeRepository,
      RecipeImageStore imageStore,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.recipeRepository = recipeRepository;
    this.imageStore = imageStore;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /**
   * Store {@code file} for {@code recipeId} on behalf of {@code actorUserId}. Throws {@link
   * RecipeNotFoundException} if the recipe is missing or soft-deleted; {@link
   * RecipeAccessDeniedException} if the caller does not own it or the recipe is SYSTEM-catalogue;
   * propagates {@code UnsupportedRecipeImageMimeException} / {@code RecipeImageStorageException}
   * from the store.
   *
   * <p>Single transactional write — updates {@code image_url} (and bumps {@code @Version}); fires
   * {@link RecipeImageUpdatedEvent} {@code AFTER_COMMIT}. Idempotent: re-uploading identical bytes
   * produces the same key and skips the row write.
   *
   * @return the persisted storage key (relative path under the configured base dir)
   */
  @Transactional
  public ImageUploadResult upload(UUID recipeId, UUID actorUserId, MultipartFile file) {
    Recipe recipe =
        recipeRepository
            .findByIdAndDeletedAtIsNull(recipeId)
            .orElseThrow(() -> new RecipeNotFoundException(recipeId));

    // Owner-only enforcement. v1 has no admin role yet (lands in tickets/core/02b), so SYSTEM-
    // catalogue recipes are unconditionally rejected — the safer default per ticket §10.
    if (recipe.getCatalogue() == Catalogue.SYSTEM) {
      throw new RecipeAccessDeniedException(
          recipeId, actorUserId, "Cannot upload images to system catalogue recipes.");
    }
    if (!recipe.getUserId().equals(actorUserId)) {
      throw new RecipeAccessDeniedException(recipeId, actorUserId);
    }

    String storageKey = imageStore.store(recipeId, file);

    String previousKey = recipe.getImageUrl();
    boolean idempotentHit = storageKey.equals(previousKey);
    if (!idempotentHit) {
      recipe.setImageUrl(storageKey);
      // saveAndFlush bumps @Version so a concurrent recipe edit will throw
      // OptimisticLockingFailureException on whichever loser commits last.
      recipeRepository.saveAndFlush(recipe);
    }

    UUID traceId = resolveTraceId();
    String publicUrl = "/api/v1/recipes/" + recipeId + "/image";
    eventPublisher.publishEvent(
        new RecipeImageUpdatedEvent(recipeId, publicUrl, actorUserId, traceId, Instant.now(clock)));

    log.info(
        "RecipeImageWrite upload recipeId={} userId={} bytes={} storageKey={} idempotent={}",
        recipeId,
        actorUserId,
        file.getSize(),
        storageKey,
        idempotentHit);

    return new ImageUploadResult(storageKey, publicUrl);
  }

  /**
   * Read-side helper exposing only the persisted storage key for the recipe; lets the serve
   * endpoint look up the file without crossing the ArchUnit "controllers don't touch entities"
   * boundary.
   *
   * @return the relative storage key, or empty when the recipe has no image (or is
   *     missing/soft-deleted — both surface as 404 to the caller)
   */
  @Transactional(readOnly = true)
  public java.util.Optional<String> findStorageKey(UUID recipeId) {
    return recipeRepository
        .findByIdAndDeletedAtIsNull(recipeId)
        .map(Recipe::getImageUrl)
        .filter(s -> s != null && !s.isBlank());
  }

  private static UUID resolveTraceId() {
    String mdc = org.slf4j.MDC.get(MDC_TRACE_ID);
    if (mdc == null || mdc.isBlank()) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(mdc);
    } catch (IllegalArgumentException ex) {
      return UUID.randomUUID();
    }
  }

  /** Carrier for the upload outcome: the storage key persisted on the recipe + the public URL. */
  public record ImageUploadResult(String storageKey, String publicUrl) {}
}
