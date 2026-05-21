package com.example.mealprep.recipe.spi;

import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

/**
 * Storage SPI for recipe images. v1 has a single implementation ({@link
 * com.example.mealprep.recipe.spi.internal.LocalFilesystemImageStore}); future S3 / CDN backends
 * swap in behind the same interface without endpoint changes.
 *
 * <p>The interface is intentionally narrow: store + load + an inner DTO for the served bytes. The
 * storage key returned by {@link #store} is opaque to callers — only the controller persists it on
 * the recipe row and feeds it back into {@link #load}. The key is module-internal; the
 * public-facing URL the frontend uses is the serve endpoint ({@code
 * /api/v1/recipes/{recipeId}/image}), wired in the controller.
 *
 * <p>Introduced in recipe-02a (C-A-053).
 */
public interface RecipeImageStore {

  /**
   * Persists the uploaded bytes for {@code recipeId}. Returns a stable storage key (filesystem path
   * relative to the configured base dir for {@code LocalFilesystemImageStore}; S3 object key when
   * that backend lands). Idempotent on identical {@code (recipeId, content-hash, extension)} tuples
   * — re-uploading the same bytes for the same recipe returns the same key without writing a
   * duplicate file.
   *
   * <p>Implementations are responsible for choosing the on-disk extension based on a magic-byte
   * probe; they MUST NOT trust {@code file.getOriginalFilename()}.
   *
   * @throws com.example.mealprep.recipe.exception.UnsupportedRecipeImageMimeException if the bytes
   *     are not a recognised image of an allow-listed type
   * @throws com.example.mealprep.recipe.exception.RecipeImageStorageException on FS / IO failure
   */
  String store(UUID recipeId, MultipartFile file);

  /**
   * Returns the byte stream + content-type for the supplied {@code storageKey}, or {@link
   * Optional#empty()} if the key resolves to a missing file (orphan-tolerant; the serve endpoint
   * maps empty → 404).
   */
  Optional<StoredImage> load(String storageKey);

  /** Streaming wrapper for served bytes. */
  record StoredImage(Resource resource, MediaType contentType) {}
}
