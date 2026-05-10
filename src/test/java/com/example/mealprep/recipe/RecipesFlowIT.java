package com.example.mealprep.recipe;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.example.mealprep.recipe.event.RecipeVersionCreatedEvent;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP flow over the recipe aggregate. Exercises {@code POST /api/v1/recipes} and {@code GET
 * /api/v1/recipes/{recipeId}} against the OpenAPI validator + asserts internal-only invariants (the
 * auto-created 'main' branch row, embedding_status='pending') via {@link JdbcTemplate}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  RecipesFlowIT.RecipeEventCaptureConfig.class
})
@ActiveProfiles("test")
class RecipesFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private RecipeEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM recipe_imports");
    jdbcTemplate.update("DELETE FROM recipe_tags");
    jdbcTemplate.update("DELETE FROM recipe_metadata");
    jdbcTemplate.update("DELETE FROM recipe_method_steps");
    jdbcTemplate.update("DELETE FROM recipe_ingredients");
    // current_branch_id FK on recipes points at recipe_branches; null it out before deleting
    // branches.
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

  // ---------------- POST /api/v1/recipes ----------------

  @Test
  void post_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/recipes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void post_returns201_withFullBody_andLocationHeader_andCreatesMainBranchRow() throws Exception {
    AuthedUser user = registerUser();

    MvcResult result =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.userId").value(user.userId().toString()))
            .andExpect(jsonPath("$.catalogue").value("USER"))
            .andExpect(jsonPath("$.dataQuality").value("USER_VERIFIED"))
            .andExpect(jsonPath("$.nutritionStatus").value("PENDING"))
            .andExpect(jsonPath("$.currentVersion").value(1))
            .andExpect(jsonPath("$.currentBranchId").exists())
            .andExpect(jsonPath("$.optimisticVersion").value(1))
            .andExpect(jsonPath("$.currentVersionBody.versionNumber").value(1))
            .andExpect(jsonPath("$.currentVersionBody.trigger").value("MANUAL_CREATE"))
            .andExpect(jsonPath("$.currentVersionBody.embeddingStatus").value("pending"))
            .andExpect(jsonPath("$.currentVersionBody.ingredients.length()").value(3))
            .andExpect(jsonPath("$.currentVersionBody.methodSteps.length()").value(3))
            .andExpect(jsonPath("$.currentVersionBody.metadata.servings").value(4))
            .andExpect(jsonPath("$.branches.length()").value(1))
            .andExpect(jsonPath("$.branches[0].name").value("main"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    UUID recipeId =
        UUID.fromString(
            objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

    // DB-level invariants — the 'main' branch is internal in 01a; the API doesn't expose it.
    Long branchCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_branches WHERE recipe_id = ? AND name = 'main'",
            Long.class,
            recipeId);
    assertThat(branchCount).isEqualTo(1L);

    String embeddingStatus =
        jdbcTemplate.queryForObject(
            "SELECT embedding_status FROM recipe_versions WHERE recipe_id = ?",
            String.class,
            recipeId);
    assertThat(embeddingStatus).isEqualTo("pending");

    // Both events published exactly once after commit.
    assertThat(eventCapture.recipeEvents()).hasSize(1);
    assertThat(eventCapture.versionEvents()).hasSize(1);
    assertThat(eventCapture.recipeEvents().get(0).recipeId()).isEqualTo(recipeId);
    assertThat(eventCapture.versionEvents().get(0).recipeId()).isEqualTo(recipeId);
  }

  @Test
  void post_returns400_whenNameBlank() throws Exception {
    AuthedUser user = registerUser();
    CreateRecipeRequest req = RecipeTestData.createRequestWithName("");

    mvc.perform(
            post("/api/v1/recipes")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void post_returns400_whenIngredientsEmpty() throws Exception {
    AuthedUser user = registerUser();
    CreateRecipeRequest req =
        new CreateRecipeRequest(
            "Empty",
            null,
            List.of(),
            RecipeTestData.defaultMethod(),
            RecipeTestData.defaultMetadata(),
            null);

    mvc.perform(
            post("/api/v1/recipes")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenMetadataMissing() throws Exception {
    AuthedUser user = registerUser();
    String body =
        objectMapper
            .writeValueAsString(RecipeTestData.defaultCreateRequest())
            .replace("\"metadata\":", "\"metadata_skipped\":");

    mvc.perform(
            post("/api/v1/recipes")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenMetadataTotalTimeMismatch() throws Exception {
    AuthedUser user = registerUser();
    CreateRecipeMetadataRequest badMeta =
        new CreateRecipeMetadataRequest(
            4, 15, 30, 999, List.of(), null, null, false, null, List.of());
    CreateRecipeRequest req =
        new CreateRecipeRequest(
            "Bad totals",
            null,
            RecipeTestData.defaultIngredients(),
            RecipeTestData.defaultMethod(),
            badMeta,
            null);

    mvc.perform(
            post("/api/v1/recipes")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  // ---------------- GET /api/v1/recipes/{recipeId} ----------------

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/recipes/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
  }

  @Test
  void get_returns404_whenMissing() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/recipes/" + UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/recipe-not-found"));
  }

  @Test
  void get_returns200_withOrderedIngredientsAndMethodSteps() throws Exception {
    AuthedUser user = registerUser();

    // Create
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andReturn();
    UUID recipeId =
        UUID.fromString(
            objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

    // Fetch — branches[] (recipe-01b) is required and contains exactly one 'main' entry.
    mvc.perform(get("/api/v1/recipes/" + recipeId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(recipeId.toString()))
        .andExpect(jsonPath("$.currentVersionBody.ingredients[0].lineOrder").value(0))
        .andExpect(jsonPath("$.currentVersionBody.ingredients[1].lineOrder").value(1))
        .andExpect(jsonPath("$.currentVersionBody.ingredients[2].lineOrder").value(2))
        .andExpect(jsonPath("$.currentVersionBody.methodSteps[0].stepNumber").value(1))
        .andExpect(jsonPath("$.currentVersionBody.methodSteps[1].stepNumber").value(2))
        .andExpect(jsonPath("$.currentVersionBody.methodSteps[2].stepNumber").value(3))
        .andExpect(jsonPath("$.branches.length()").value(1))
        .andExpect(jsonPath("$.branches[0].name").value("main"))
        .andExpect(jsonPath("$.branches[0].recipeId").value(recipeId.toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void get_returns404_whenSoftDeleted() throws Exception {
    AuthedUser user = registerUser();

    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andReturn();
    UUID recipeId =
        UUID.fromString(
            objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

    jdbcTemplate.update("UPDATE recipe_recipes SET deleted_at = now() WHERE id = ?", recipeId);

    mvc.perform(get("/api/v1/recipes/" + recipeId).cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class RecipeEventCaptureConfig {
    @Bean
    RecipeEventCapture recipeEventCapture() {
      return new RecipeEventCapture();
    }
  }

  static class RecipeEventCapture {
    private final List<RecipeCreatedEvent> recipeEvents = new CopyOnWriteArrayList<>();
    private final List<RecipeVersionCreatedEvent> versionEvents = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onRecipeCreated(RecipeCreatedEvent event) {
      recipeEvents.add(event);
    }

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onRecipeVersionCreated(RecipeVersionCreatedEvent event) {
      versionEvents.add(event);
    }

    public List<RecipeCreatedEvent> recipeEvents() {
      return recipeEvents;
    }

    public List<RecipeVersionCreatedEvent> versionEvents() {
      return versionEvents;
    }

    public void clear() {
      recipeEvents.clear();
      versionEvents.clear();
    }
  }
}
