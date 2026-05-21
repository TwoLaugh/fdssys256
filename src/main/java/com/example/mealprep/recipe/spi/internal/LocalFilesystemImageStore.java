package com.example.mealprep.recipe.spi.internal;

import com.example.mealprep.recipe.config.RecipeImageStorageProperties;
import com.example.mealprep.recipe.exception.RecipeImageStorageException;
import com.example.mealprep.recipe.exception.UnsupportedRecipeImageMimeException;
import com.example.mealprep.recipe.spi.RecipeImageStore;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * v1 local-filesystem implementation of {@link RecipeImageStore}. Files land under {@code
 * <baseDir>/recipes/<first-2-chars-of-recipeId>/<recipeId>-<hash>.<ext>}.
 *
 * <p>Layout rationale:
 *
 * <ul>
 *   <li>{@code first-2-chars-of-recipeId} shards directories — no single directory accumulates a
 *       million files in a large catalogue. Cheap convention; future-proofs.
 *   <li>{@code <recipeId>-<hash>.<ext>} keeps {@code ls}-based debugging easy (the recipe id is in
 *       the filename) AND gives content-hash idempotency: re-uploading the same bytes for the same
 *       recipe writes to the same path, so we detect the existing file and skip the disk write.
 *   <li>{@code <ext>} is derived from the Tika magic-byte probe, not from the original filename.
 *       The original filename is never trusted (CWE-22 path traversal, CWE-434 unrestricted file
 *       upload).
 * </ul>
 *
 * <p>Allow-list is sourced from {@link RecipeImageStorageProperties#getAllowedMimeTypes()}; default
 * is JPEG / PNG / WebP. The Tika probe is authoritative — the browser-supplied content-type is a
 * hint only.
 *
 * <p>Introduced in recipe-02a.
 */
@Component
public class LocalFilesystemImageStore implements RecipeImageStore {

  private static final Logger log = LoggerFactory.getLogger(LocalFilesystemImageStore.class);

  /** Sub-directory under {@code baseDir} where recipe images live. */
  static final String RECIPES_SUBDIR = "recipes";

  /** Length of the hex-sliced hash suffix used in filenames (collision-resistant at this size). */
  static final int HASH_HEX_LENGTH = 16;

  /** Probe window for Tika's magic-byte detection. */
  private static final int TIKA_PROBE_BYTES = 512;

  /** MIME → on-disk extension. Single source of truth for extension selection. */
  private static final Map<String, String> EXTENSION_BY_MIME =
      Map.of(
          "image/jpeg", "jpg",
          "image/png", "png",
          "image/webp", "webp");

  private final RecipeImageStorageProperties properties;
  private final Tika tika;

  public LocalFilesystemImageStore(RecipeImageStorageProperties properties) {
    this.properties = properties;
    this.tika = new Tika();
  }

  /**
   * Bean-init permission check. Ensures {@code baseDir} exists (creates it if missing) and is
   * writable. A startup failure here is preferable to a mid-runtime FS exception on the first
   * upload.
   */
  @PostConstruct
  public void ensureBaseDirIsUsable() {
    Path baseDir = properties.getBaseDir().toAbsolutePath().normalize();
    try {
      Files.createDirectories(baseDir);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Cannot create RecipeImageStore base directory: " + baseDir, ex);
    }
    if (!Files.isWritable(baseDir)) {
      throw new IllegalStateException(
          "RecipeImageStore base directory is not writable: " + baseDir);
    }
    log.info("RecipeImageStore base directory ready: {}", baseDir);
  }

  @Override
  public String store(UUID recipeId, MultipartFile file) {
    if (recipeId == null) {
      throw new IllegalArgumentException("recipeId must not be null");
    }
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Uploaded file must not be empty.");
    }

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException ex) {
      throw new RecipeImageStorageException("Failed to read uploaded bytes", ex);
    }

    String detectedMime = tika.detect(probeWindow(bytes));
    if (detectedMime == null
        || !properties.getAllowedMimeTypes().contains(detectedMime.toLowerCase(Locale.ROOT))) {
      throw new UnsupportedRecipeImageMimeException(detectedMime);
    }
    String extension = EXTENSION_BY_MIME.get(detectedMime.toLowerCase(Locale.ROOT));
    if (extension == null) {
      // Defensive — the allow-list is the property's; if a non-mapped MIME slipped past it, the
      // map and the property have diverged. Treat as unsupported.
      throw new UnsupportedRecipeImageMimeException(detectedMime);
    }

    String hash = sha256Hex(bytes).substring(0, HASH_HEX_LENGTH);
    String shard = recipeId.toString().substring(0, 2);
    String filename = recipeId + "-" + hash + "." + extension;
    String relativeKey = RECIPES_SUBDIR + "/" + shard + "/" + filename;

    Path targetDir =
        properties.getBaseDir().toAbsolutePath().normalize().resolve(RECIPES_SUBDIR).resolve(shard);
    Path targetFile = targetDir.resolve(filename);

    try {
      Files.createDirectories(targetDir);
    } catch (IOException ex) {
      throw new RecipeImageStorageException(
          "Cannot create image shard directory: " + targetDir, ex);
    }

    if (Files.exists(targetFile)) {
      // Hash-based idempotency: same recipe + same bytes → same path → no re-write needed.
      log.debug("RecipeImageStore.store idempotent hit recipeId={} key={}", recipeId, relativeKey);
      return relativeKey;
    }

    try (InputStream in = file.getInputStream()) {
      Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ex) {
      throw new RecipeImageStorageException(
          "Failed to persist uploaded image to " + targetFile, ex);
    }
    log.info(
        "RecipeImageStore.store wrote recipeId={} key={} bytes={}",
        recipeId,
        relativeKey,
        bytes.length);
    return relativeKey;
  }

  @Override
  public Optional<StoredImage> load(String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      return Optional.empty();
    }
    Path baseDir = properties.getBaseDir().toAbsolutePath().normalize();
    Path resolved = baseDir.resolve(storageKey).normalize();
    // Defence-in-depth path-traversal guard: ensure the resolved file is still under baseDir.
    if (!resolved.startsWith(baseDir)) {
      log.warn(
          "RecipeImageStore.load rejected key outside baseDir baseDir={} key={}",
          baseDir,
          storageKey);
      return Optional.empty();
    }
    if (!Files.isRegularFile(resolved)) {
      return Optional.empty();
    }
    MediaType mediaType = mediaTypeForExtension(extensionOf(storageKey));
    try {
      Resource resource = new UrlResource(resolved.toUri());
      return Optional.of(new StoredImage(resource, mediaType));
    } catch (Exception ex) {
      throw new RecipeImageStorageException("Failed to open stored image: " + resolved, ex);
    }
  }

  // ---------------- helpers ----------------

  private static byte[] probeWindow(byte[] bytes) {
    int len = Math.min(TIKA_PROBE_BYTES, bytes.length);
    byte[] window = new byte[len];
    System.arraycopy(bytes, 0, window, 0, len);
    return window;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is mandated by the JDK spec; this is unreachable.
      throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
    }
  }

  private static String extensionOf(String storageKey) {
    int dot = storageKey.lastIndexOf('.');
    if (dot < 0 || dot == storageKey.length() - 1) {
      return "";
    }
    return storageKey.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static MediaType mediaTypeForExtension(String ext) {
    return switch (ext) {
      case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
      case "png" -> MediaType.IMAGE_PNG;
      case "webp" -> MediaType.parseMediaType("image/webp");
      default -> MediaType.APPLICATION_OCTET_STREAM;
    };
  }
}
