package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.recipe.event.RecipeImageUpdatedEvent;
import com.example.mealprep.recipe.testdata.RecipeImageTestFixtures;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP flow over the recipe-image upload + serve endpoints. Exercises the edge-case checklist
 * in {@code tickets/recipe/02a-image-storage.md} — happy paths, MIME guard, owner-only enforcement,
 * idempotency, anonymous-readable GET, and the AFTER_COMMIT event.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, RecipeImageControllerIT.RecipeImageEventCaptureConfig.class})
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      // Pin a known per-test directory so we can introspect files written on disk.
      "mealprep.recipe.image-storage.base-dir=${java.io.tmpdir}/mealprep-test-recipe-images-it"
    })
class RecipeImageControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private RecipeImageEventCapture eventCapture;

  @Value("${mealprep.recipe.image-storage.base-dir}")
  private Path baseDir;

  @AfterEach
  void cleanup() throws Exception {
    jdbcTemplate.update("DELETE FROM recipe_imports");
    jdbcTemplate.update("DELETE FROM recipe_tags");
    jdbcTemplate.update("DELETE FROM recipe_metadata");
    jdbcTemplate.update("DELETE FROM recipe_method_steps");
    jdbcTemplate.update("DELETE FROM recipe_ingredients");
    jdbcTemplate.update("UPDATE recipe_recipes SET current_branch_id = NULL");
    jdbcTemplate.update("DELETE FROM recipe_versions");
    jdbcTemplate.update("DELETE FROM recipe_branches");
    jdbcTemplate.update("DELETE FROM recipe_recipes");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

  // ---------------- helpers ----------------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userIdJson =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userIdJson), cookie);
  }

  private UUID createRecipeAs(AuthedUser user) throws Exception {
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(
        objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
  }

  // ---------------- POST happy path ----------------

  @Test
  void upload_returns200_writesFileToShardedDir_andPersistsKey() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);
    eventCapture.clear();

    MockMultipartFile file =
        new MockMultipartFile("file", "hero.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg());

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image").file(file).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.imageUrl").value("/api/v1/recipes/" + recipeId + "/image"))
        .andExpect(jsonPath("$.contentType").value("image/jpeg"));

    String shard = recipeId.toString().substring(0, 2);
    Path shardDir = baseDir.resolve("recipes").resolve(shard);
    assertThat(Files.exists(shardDir)).isTrue();
    long count =
        Files.list(shardDir)
            .filter(p -> p.getFileName().toString().startsWith(recipeId.toString()))
            .count();
    assertThat(count).isEqualTo(1L);

    String storedKey =
        jdbcTemplate.queryForObject(
            "SELECT image_url FROM recipe_recipes WHERE id = ?", String.class, recipeId);
    assertThat(storedKey).startsWith("recipes/" + shard + "/" + recipeId + "-").endsWith(".jpg");

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).recipeId()).isEqualTo(recipeId);
    assertThat(eventCapture.events().get(0).imageUrl())
        .isEqualTo("/api/v1/recipes/" + recipeId + "/image");
  }

  @Test
  void upload_PngLandsWithPngExtension_andContentTypeIsImagePng() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);

    MockMultipartFile file =
        new MockMultipartFile("file", "hero.png", "image/png", RecipeImageTestFixtures.png());

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image").file(file).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contentType").value("image/png"));

    String storedKey =
        jdbcTemplate.queryForObject(
            "SELECT image_url FROM recipe_recipes WHERE id = ?", String.class, recipeId);
    assertThat(storedKey).endsWith(".png");
  }

  @Test
  void upload_WebpLandsWithWebpExtension() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);

    MockMultipartFile file =
        new MockMultipartFile("file", "hero.webp", "image/webp", RecipeImageTestFixtures.webp());

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image").file(file).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contentType").value("image/webp"));
  }

  @Test
  void upload_reuploadingSameBytes_isIdempotent() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);

    byte[] bytes = RecipeImageTestFixtures.jpeg();

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", bytes))
                .cookie(user.cookie()))
        .andExpect(status().isOk());
    String firstKey =
        jdbcTemplate.queryForObject(
            "SELECT image_url FROM recipe_recipes WHERE id = ?", String.class, recipeId);

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", bytes))
                .cookie(user.cookie()))
        .andExpect(status().isOk());
    String secondKey =
        jdbcTemplate.queryForObject(
            "SELECT image_url FROM recipe_recipes WHERE id = ?", String.class, recipeId);

    assertThat(secondKey).isEqualTo(firstKey);
    String shard = recipeId.toString().substring(0, 2);
    long fileCount = Files.list(baseDir.resolve("recipes").resolve(shard)).count();
    assertThat(fileCount).isEqualTo(1L);
  }

  // ---------------- POST 4xx paths ----------------

  @Test
  void upload_returns401_whenAnonymous() throws Exception {
    UUID recipeId = UUID.randomUUID();
    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(
                    new MockMultipartFile(
                        "file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void upload_returns404_whenRecipeMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            multipart("/api/v1/recipes/" + UUID.randomUUID() + "/image")
                .file(
                    new MockMultipartFile(
                        "file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg()))
                .cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void upload_returns404_whenRecipeSoftDeleted() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);
    jdbcTemplate.update("UPDATE recipe_recipes SET deleted_at = now() WHERE id = ?", recipeId);

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(
                    new MockMultipartFile(
                        "file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg()))
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void upload_returns403_whenCallerIsNotOwner() throws Exception {
    AuthedUser alice = registerUser();
    UUID recipeId = createRecipeAs(alice);
    AuthedUser bob = registerUser();

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(
                    new MockMultipartFile(
                        "file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg()))
                .cookie(bob.cookie()))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/recipe-access-denied"));
  }

  @Test
  void upload_returns403_whenSystemCatalogue() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);
    jdbcTemplate.update("UPDATE recipe_recipes SET catalogue = 'SYSTEM' WHERE id = ?", recipeId);

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(
                    new MockMultipartFile(
                        "file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg()))
                .cookie(user.cookie()))
        .andExpect(status().isForbidden());
  }

  @Test
  void upload_returns415_whenBytesAreNotAnImage() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(
                    new MockMultipartFile(
                        "file", "evil.jpg", "image/jpeg", RecipeImageTestFixtures.nonImage()))
                .cookie(user.cookie()))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-image-unsupported-mime"));
  }

  @Test
  void upload_returns400_whenFileIsEmpty() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[0]))
                .cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }

  // ---------------- GET (serve) ----------------

  @Test
  void serve_returns200_anonymousReadable_withImageContentTypeAndCacheControl() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(
                    new MockMultipartFile(
                        "file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg()))
                .cookie(user.cookie()))
        .andExpect(status().isOk());

    // No auth cookie — anonymous read.
    mvc.perform(get("/api/v1/recipes/" + recipeId + "/image"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=86400")))
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
  }

  @Test
  void serve_returns404_whenRecipeHasNoImage() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);

    mvc.perform(get("/api/v1/recipes/" + recipeId + "/image"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-image-not-found"));
  }

  @Test
  void serve_returns404_whenRecipeMissing() throws Exception {
    mvc.perform(get("/api/v1/recipes/" + UUID.randomUUID() + "/image"))
        .andExpect(status().isNotFound());
  }

  @Test
  void serve_returns404_whenFileMissingOnDisk_orphanTolerant() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);
    // Point the DB row at a phantom storage key — file does not exist on disk.
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET image_url = ? WHERE id = ?",
        "recipes/zz/" + recipeId + "-deadbeefdeadbeef.jpg",
        recipeId);

    mvc.perform(get("/api/v1/recipes/" + recipeId + "/image")).andExpect(status().isNotFound());
  }

  // ---------------- RecipeDto.imageUrl ----------------

  @Test
  void recipeDto_imageUrl_isNullBeforeUpload_andPopulatedAfter() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipeAs(user);

    mvc.perform(get("/api/v1/recipes/" + recipeId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imageUrl").doesNotExist());

    mvc.perform(
            multipart("/api/v1/recipes/" + recipeId + "/image")
                .file(
                    new MockMultipartFile(
                        "file", "x.jpg", "image/jpeg", RecipeImageTestFixtures.jpeg()))
                .cookie(user.cookie()))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/recipes/" + recipeId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imageUrl").value("/api/v1/recipes/" + recipeId + "/image"));
  }

  // ---------------- AFTER_COMMIT event capture ----------------

  @TestConfiguration
  static class RecipeImageEventCaptureConfig {
    @Bean
    RecipeImageEventCapture recipeImageEventCapture() {
      return new RecipeImageEventCapture();
    }
  }

  static class RecipeImageEventCapture {
    private final List<RecipeImageUpdatedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onRecipeImageUpdated(RecipeImageUpdatedEvent event) {
      events.add(event);
    }

    public List<RecipeImageUpdatedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
