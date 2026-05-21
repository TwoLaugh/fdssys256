package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.RecipeImageDto;
import com.example.mealprep.recipe.config.RecipeImageStorageProperties;
import com.example.mealprep.recipe.domain.service.internal.RecipeImageWriteService;
import com.example.mealprep.recipe.exception.RecipeImageNotFoundException;
import com.example.mealprep.recipe.spi.RecipeImageStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for recipe image upload + serve. Per ticket {@code tickets/recipe/02a-image-storage.md}
 * (frontend-readiness Tier A). Closes the audit gaps C-A-053, C-B-026, C-E-022.
 *
 * <p>{@code POST} requires authentication (owner-only — non-owner returns 403); {@code GET} is
 * anonymous-accessible (recipe images are public assets given anyone-can-read on recipes; URL is
 * unguessable but not secret). The split-by-verb permit on the path is wired in {@code
 * AuthSecurityConfig}.
 *
 * <p>The multipart endpoint follows the new project-wide convention introduced by this ticket:
 * {@code @PostMapping(consumes = MULTIPART_FORM_DATA_VALUE)} + a direct {@link MultipartFile}
 * parameter (no wrapper DTO). Future multipart endpoints (avatar upload, etc.) should follow the
 * same shape; consider extracting {@code MultipartUploadConventions.md} if a third multipart
 * endpoint lands.
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}/image")
@Tag(name = "Recipes")
public class RecipeImageController {

  private static final Logger log = LoggerFactory.getLogger(RecipeImageController.class);

  private final RecipeImageWriteService writeService;
  private final RecipeImageStore imageStore;
  private final RecipeImageStorageProperties properties;
  private final CurrentUserResolver currentUserResolver;

  public RecipeImageController(
      RecipeImageWriteService writeService,
      RecipeImageStore imageStore,
      RecipeImageStorageProperties properties,
      CurrentUserResolver currentUserResolver) {
    this.writeService = writeService;
    this.imageStore = imageStore;
    this.properties = properties;
    this.currentUserResolver = currentUserResolver;
  }

  // ---------------- POST (upload) ----------------

  @PostMapping(
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Upload a recipe image. Multipart/form-data with a single 'file' part; max 5MB; MIME"
              + " allow-list (JPEG, PNG, WebP); owner-only.")
  public ResponseEntity<RecipeImageDto> upload(
      @PathVariable UUID recipeId, @RequestPart("file") MultipartFile file) {
    UUID actorUserId = requireCurrentUserId();

    if (file == null || file.isEmpty()) {
      // 400 — zero-byte / absent upload. The DTO-less multipart endpoint can't use
      // @Valid/@NotNull, so the empty check lives here.
      throw new IllegalArgumentException("Uploaded file must not be empty.");
    }

    RecipeImageWriteService.ImageUploadResult result =
        writeService.upload(recipeId, actorUserId, file);
    String contentType = mimeForExtension(extensionOf(result.storageKey()));

    return ResponseEntity.ok(new RecipeImageDto(result.publicUrl(), file.getSize(), contentType));
  }

  // ---------------- GET (serve) ----------------

  @GetMapping(produces = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp"})
  @Operation(summary = "Stream the recipe image. Anonymous-accessible; 404 when no image stored.")
  public ResponseEntity<org.springframework.core.io.Resource> serve(@PathVariable UUID recipeId) {
    String storageKey =
        writeService
            .findStorageKey(recipeId)
            .orElseThrow(() -> new RecipeImageNotFoundException(recipeId));
    RecipeImageStore.StoredImage stored =
        imageStore
            .load(storageKey)
            .orElseThrow(
                () -> {
                  // Orphan-tolerant per ticket §16: image_url is set but file is missing from
                  // disk. Surface a 404 (mainly a safety check for restore-from-backup scenarios).
                  log.warn(
                      "RecipeImage serve orphan recipeId={} storageKey={}", recipeId, storageKey);
                  return new RecipeImageNotFoundException(recipeId);
                });

    Duration maxAge = properties.getCacheMaxAge();
    return ResponseEntity.ok()
        .contentType(stored.contentType())
        .cacheControl(CacheControl.maxAge(maxAge).cachePublic().immutable())
        .body(stored.resource());
  }

  // ---------------- helpers ----------------

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }

  private static String extensionOf(String storageKey) {
    int dot = storageKey.lastIndexOf('.');
    if (dot < 0 || dot == storageKey.length() - 1) {
      return "";
    }
    return storageKey.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String mimeForExtension(String ext) {
    return switch (ext) {
      case "jpg", "jpeg" -> "image/jpeg";
      case "png" -> "image/png";
      case "webp" -> "image/webp";
      default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
    };
  }
}
