package com.example.mealprep.recipe.config;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the recipe-image storage subsystem. Bound from {@code
 * mealprep.recipe.image-storage.*}.
 *
 * <p>Defaults are tuned for the v1 single-host deployment: 5 MiB upload cap, three image MIME
 * types, base directory under {@code ./data/recipe-images}, and a 24 h browser cache (safe because
 * stored filenames are content-hashed, so the URL effectively changes whenever the bytes change).
 *
 * <p>Introduced in recipe-02a.
 */
@Configuration
@ConfigurationProperties(prefix = "mealprep.recipe.image-storage")
@Validated
public class RecipeImageStorageProperties {

  /**
   * Filesystem directory under which recipe image files are stored. Must exist OR be creatable at
   * bean init; the {@code LocalFilesystemImageStore} constructor performs that check and throws on
   * permission failure.
   */
  @NotNull private Path baseDir = Paths.get("./data/recipe-images");

  /**
   * Maximum size of a single uploaded image. Spring's multipart resolver also enforces a limit
   * (configured by {@code spring.servlet.multipart.max-file-size}); this property is the
   * application-layer cross-check and must be {@code <=} the multipart limit. 5 MiB matches the
   * frontend's "scale-before-upload" guidance.
   */
  @NotNull private DataSize maxFileSize = DataSize.ofMegabytes(5);

  /**
   * Allowed image MIME types (Tika-probed; the browser-supplied content-type is a hint only).
   * Defaults to JPEG / PNG / WebP — the three formats every modern browser renders without a
   * polyfill.
   */
  @NotNull private List<String> allowedMimeTypes = List.of("image/jpeg", "image/png", "image/webp");

  /**
   * {@code Cache-Control: max-age=} value the GET endpoint sends. Safe to set high because
   * filenames are content-hashed and therefore effectively immutable. Default 24 h.
   */
  @NotNull private Duration cacheMaxAge = Duration.ofHours(24);

  public Path getBaseDir() {
    return baseDir;
  }

  public void setBaseDir(Path baseDir) {
    this.baseDir = baseDir;
  }

  public DataSize getMaxFileSize() {
    return maxFileSize;
  }

  public void setMaxFileSize(DataSize maxFileSize) {
    this.maxFileSize = maxFileSize;
  }

  public List<String> getAllowedMimeTypes() {
    return allowedMimeTypes;
  }

  public void setAllowedMimeTypes(List<String> allowedMimeTypes) {
    this.allowedMimeTypes = allowedMimeTypes;
  }

  public Duration getCacheMaxAge() {
    return cacheMaxAge;
  }

  public void setCacheMaxAge(Duration cacheMaxAge) {
    this.cacheMaxAge = cacheMaxAge;
  }
}
