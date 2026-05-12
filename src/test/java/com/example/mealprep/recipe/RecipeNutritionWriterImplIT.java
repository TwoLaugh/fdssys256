package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cross-module SPI bridge IT. Verifies recipe-01g's {@code RecipeNutritionWriterImpl} (a
 * {@code @Component @ConditionalOnClass}) wires in the full Spring context — displacing
 * nutrition-01f's {@code NoopRecipeNutritionWriterConfiguration} — AND that the end-to-end wire
 * fires:
 *
 * <ol>
 *   <li>Recipe manual-edit publishes {@code RecipeUpdatedEvent} {@code AFTER_COMMIT}.
 *   <li>Nutrition's {@code RecipeEventListener} picks it up, runs {@code
 *       recalculateForEvolvedRecipe}, and calls {@code
 *       RecipeNutritionWriter.writeNutritionPerServing}.
 *   <li>The recipe-01g bridge delegates to {@code RecipeWriteApi.updateNutritionStatus} which
 *       persists {@code nutrition_per_serving} JSON on the version row.
 * </ol>
 *
 * <p>Per recipe-01g ticket §{@code RecipeNutritionWriterImpl} (bridge from recipe-01f) and
 * acceptance checklist line 560 ("End-to-end SPI bridge IT passes").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class RecipeNutritionWriterImplIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private RecipeNutritionWriter writer;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_ingredient_mapping");
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
  }

  @Test
  void bridgeImpl_isWired_notNoop() {
    // The @ConditionalOnClass(name = "...") bridge in recipe.spi.internal is now on the classpath
    // (recipe-01g) — it displaces nutrition-01f's NoopRecipeNutritionWriterConfiguration via the
    // Noop's @ConditionalOnMissingBean defer.
    assertThat(writer.getClass().getName()).contains("RecipeNutritionWriterImpl");
    assertThat(writer.getClass().getName()).doesNotContain("Noop");
  }

  @Test
  void endToEnd_manualEdit_triggersNutritionListener_andBridgeWritesNutritionStatus()
      throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = createRecipe(user);
    long optimisticVersion = readOptimisticVersion(recipeId);

    // Manual edit publishes RecipeUpdatedEvent AFTER_COMMIT → nutrition RecipeEventListener →
    // bridge writer → RecipeWriteApi.updateNutritionStatus → row update.
    UpdateRecipeManualEditRequest editReq =
        RecipeTestData.defaultManualEditRequest(optimisticVersion);
    mvc.perform(
            put("/api/v1/recipes/" + recipeId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(editReq)))
        .andExpect(status().isOk());

    // The nutrition listener runs AFTER_COMMIT and is synchronous within the same thread for
    // SpringBootTest (no async executor configured). The recipe's nutrition listener calls
    // RecipeNutritionWriter.writeNutritionPerServing — recipe-01g bridge → RecipeWriteApi.update.
    // Per nutrition listener's error-swallowing contract, even if the calc cannot resolve every
    // ingredient (test cache is empty), the listener still calls the writer at least when at least
    // one ingredient resolves. The version row gets nutrition_per_serving populated.
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_versions WHERE recipe_id = ?"
                + " AND nutrition_per_serving IS NOT NULL",
            Long.class,
            recipeId);
    assertThat(count).isGreaterThanOrEqualTo(1L);
  }

  // ---------------- helpers ----------------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "bridge-" + AuthTestData.shortId();
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

  private UUID createRecipe(AuthedUser user) throws Exception {
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode tree = objectMapper.readTree(created.getResponse().getContentAsString());
    return UUID.fromString(tree.get("id").asText());
  }

  private long readOptimisticVersion(UUID recipeId) {
    Long v =
        jdbcTemplate.queryForObject(
            "SELECT optimistic_version FROM recipe_recipes WHERE id = ?", Long.class, recipeId);
    return v == null ? 0L : v;
  }
}
