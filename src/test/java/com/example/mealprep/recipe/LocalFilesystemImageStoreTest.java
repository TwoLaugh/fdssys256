package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.recipe.config.RecipeImageStorageProperties;
import com.example.mealprep.recipe.exception.RecipeImageStorageException;
import com.example.mealprep.recipe.exception.UnsupportedRecipeImageMimeException;
import com.example.mealprep.recipe.spi.RecipeImageStore;
import com.example.mealprep.recipe.spi.internal.LocalFilesystemImageStore;
import com.example.mealprep.recipe.testdata.RecipeImageTestFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/**
 * Unit tests for {@link LocalFilesystemImageStore} — covers the store/load happy paths plus the
 * security-critical MIME guard, hash-based idempotency, sharded directory layout, path-traversal
 * defence, missing-file load, and bean-init permission check.
 */
class LocalFilesystemImageStoreTest {

  @TempDir Path tempDir;

  private RecipeImageStorageProperties properties;
  private LocalFilesystemImageStore store;

  @BeforeEach
  void setUp() {
    properties = new RecipeImageStorageProperties();
    properties.setBaseDir(tempDir);
    properties.setMaxFileSize(DataSize.ofMegabytes(5));
    properties.setAllowedMimeTypes(List.of("image/jpeg", "image/png", "image/webp"));
    properties.setCacheMaxAge(Duration.ofHours(24));

    store = new LocalFilesystemImageStore(properties);
    store.ensureBaseDirIsUsable();
  }

  // ---------------- store ----------------

  @Test
  void store_writesJpegUnderShardedDirectory_returnsRelativeKey() {
    UUID recipeId = UUID.fromString("ab123456-0000-0000-0000-000000000000");
    MockMultipartFile file =
        new MockMultipartFile("file", "any.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg());

    String key = store.store(recipeId, file);

    assertThat(key).startsWith("recipes/ab/" + recipeId + "-").endsWith(".jpg");
    Path written = tempDir.resolve(key);
    assertThat(Files.exists(written)).isTrue();
  }

  @Test
  void store_writesPng_chooseExtensionByMagicBytes_notFilename() {
    UUID recipeId = UUID.fromString("cd123456-0000-0000-0000-000000000000");
    // Deliberately mismatched: filename .jpg, browser-mime image/jpeg, but bytes are PNG.
    // Tika probe wins → extension must be .png.
    MockMultipartFile file =
        new MockMultipartFile("file", "tricky.jpg", "image/jpeg", RecipeImageTestFixtures.png());

    String key = store.store(recipeId, file);

    assertThat(key).endsWith(".png");
  }

  @Test
  void store_writesWebp() {
    UUID recipeId = UUID.fromString("ef123456-0000-0000-0000-000000000000");
    MockMultipartFile file =
        new MockMultipartFile("file", "x.webp", "image/webp", RecipeImageTestFixtures.webp());

    String key = store.store(recipeId, file);

    assertThat(key).startsWith("recipes/ef/").endsWith(".webp");
  }

  @Test
  void store_rejectsNonImage_with415Exception() {
    UUID recipeId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile("file", "evil.jpg", "image/jpeg", RecipeImageTestFixtures.nonImage());

    assertThatExceptionOfType(UnsupportedRecipeImageMimeException.class)
        .isThrownBy(() -> store.store(recipeId, file));
  }

  @Test
  void store_rejectsEmptyFile() {
    UUID recipeId = UUID.randomUUID();
    MockMultipartFile file = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

    assertThatThrownBy(() -> store.store(recipeId, file))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void store_rejectsNullRecipeId() {
    MockMultipartFile file =
        new MockMultipartFile("file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg());

    assertThatThrownBy(() -> store.store(null, file)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void store_isIdempotent_onIdenticalBytesForSameRecipe() throws IOException {
    UUID recipeId = UUID.randomUUID();
    byte[] bytes = RecipeImageTestFixtures.jpeg();

    String key1 =
        store.store(recipeId, new MockMultipartFile("file", "a.jpg", "image/jpeg", bytes));
    String key2 =
        store.store(recipeId, new MockMultipartFile("file", "a.jpg", "image/jpeg", bytes));

    assertThat(key1).isEqualTo(key2);
    // Sharded subdir contains exactly one file (no duplicate written).
    String shard = recipeId.toString().substring(0, 2);
    long fileCount = Files.list(tempDir.resolve("recipes").resolve(shard)).count();
    assertThat(fileCount).isEqualTo(1L);
  }

  @Test
  void store_differentBytesSameRecipe_writesNewFile() throws IOException {
    UUID recipeId = UUID.randomUUID();

    String key1 =
        store.store(
            recipeId,
            new MockMultipartFile("file", "a.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg()));
    String key2 =
        store.store(
            recipeId,
            new MockMultipartFile(
                "file", "b.jpg", "image/jpeg", RecipeImageTestFixtures.alternateJpeg()));

    assertThat(key1).isNotEqualTo(key2);
    String shard = recipeId.toString().substring(0, 2);
    long fileCount = Files.list(tempDir.resolve("recipes").resolve(shard)).count();
    assertThat(fileCount).isEqualTo(2L);
  }

  @Test
  void store_propagatesIoFailure_asRecipeImageStorageException() {
    UUID recipeId = UUID.randomUUID();
    // MockMultipartFile that throws on getBytes() exercises the IO catch block.
    MockMultipartFile failing =
        new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2}) {
          @Override
          public byte[] getBytes() throws IOException {
            throw new IOException("simulated read failure");
          }
        };

    assertThatExceptionOfType(RecipeImageStorageException.class)
        .isThrownBy(() -> store.store(recipeId, failing));
  }

  // ---------------- load ----------------

  @Test
  void load_returnsResource_withCorrectMediaType_forJpeg() {
    UUID recipeId = UUID.fromString("ab123456-0000-0000-0000-000000000001");
    String key =
        store.store(
            recipeId,
            new MockMultipartFile("file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg()));

    Optional<RecipeImageStore.StoredImage> loaded = store.load(key);

    assertThat(loaded).isPresent();
    assertThat(loaded.get().contentType().toString()).isEqualTo("image/jpeg");
    assertThat(loaded.get().resource().exists()).isTrue();
  }

  @Test
  void load_returnsResource_withWebpMediaType() {
    UUID recipeId = UUID.fromString("ab123456-0000-0000-0000-000000000002");
    String key =
        store.store(
            recipeId,
            new MockMultipartFile("file", "x.webp", "image/webp", RecipeImageTestFixtures.webp()));

    Optional<RecipeImageStore.StoredImage> loaded = store.load(key);

    assertThat(loaded).isPresent();
    assertThat(loaded.get().contentType().toString()).isEqualTo("image/webp");
  }

  @Test
  void load_emptyForMissingFile() {
    Optional<RecipeImageStore.StoredImage> loaded =
        store.load("recipes/zz/" + UUID.randomUUID() + "-deadbeefdeadbeef.jpg");
    assertThat(loaded).isEmpty();
  }

  @Test
  void load_emptyForBlankOrNullKey() {
    assertThat(store.load(null)).isEmpty();
    assertThat(store.load("")).isEmpty();
    assertThat(store.load("   ")).isEmpty();
  }

  @Test
  void load_rejectsKeysThatEscapeBaseDir() {
    // Defence-in-depth path-traversal guard.
    Optional<RecipeImageStore.StoredImage> loaded = store.load("../../../etc/passwd");
    assertThat(loaded).isEmpty();
  }

  // ---------------- bean init ----------------

  @Test
  void ensureBaseDirIsUsable_createsMissingDirectory() throws IOException {
    Path nested = tempDir.resolve("nested").resolve("deeply");
    RecipeImageStorageProperties props = new RecipeImageStorageProperties();
    props.setBaseDir(nested);
    LocalFilesystemImageStore fresh = new LocalFilesystemImageStore(props);

    fresh.ensureBaseDirIsUsable();

    assertThat(Files.exists(nested)).isTrue();
    assertThat(Files.isDirectory(nested)).isTrue();
  }

  @Test
  void ensureBaseDirIsUsable_failsWhenBaseDirIsActuallyAFile() throws IOException {
    Path notADir = tempDir.resolve("just-a-file.txt");
    Files.writeString(notADir, "i am not a directory");

    RecipeImageStorageProperties props = new RecipeImageStorageProperties();
    props.setBaseDir(notADir);
    LocalFilesystemImageStore fresh = new LocalFilesystemImageStore(props);

    assertThatThrownBy(fresh::ensureBaseDirIsUsable).isInstanceOf(IllegalStateException.class);
  }
}
