package com.example.mealprep.recipe;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import com.example.mealprep.recipe.event.RecipeUpdatedEvent;
import com.example.mealprep.recipe.event.RecipeVersionCreatedEvent;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Full HTTP flow over the recipe manual-edit endpoint. Verifies the v2 INSERT, append-only
 * versioning (parent body rows untouched), the {@code RecipeUpdatedEvent} + {@code
 * RecipeVersionCreatedEvent} emission, and the OpenAPI shape via the swagger-validator filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  RecipeManualEditFlowIT.UpdateEventCaptureConfig.class
})
@ActiveProfiles("test")
class RecipeManualEditFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private UpdateEventCapture eventCapture;

  @AfterEach
  void cleanup() {
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

  private record CreatedRecipe(UUID recipeId, long optimisticVersion) {}

  private CreatedRecipe createRecipe(AuthedUser user) throws Exception {
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
    return new CreatedRecipe(
        UUID.fromString(tree.get("id").asText()), tree.get("optimisticVersion").asLong());
  }

  // ---------------- Tests ----------------

  @Test
  void put_returns401_whenAnonymous() throws Exception {
    UpdateRecipeManualEditRequest req = RecipeTestData.defaultManualEditRequest(1L);
    mvc.perform(
            put("/api/v1/recipes/" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void put_returns200_writesV2_keepsV1BodyIntact_publishesEvents() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user);
    eventCapture.clear();

    long ingredientsBefore =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_ingredients i"
                + " JOIN recipe_versions v ON v.id = i.version_id"
                + " WHERE v.recipe_id = ?",
            Long.class,
            r.recipeId());
    long methodBefore =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_method_steps s"
                + " JOIN recipe_versions v ON v.id = s.version_id"
                + " WHERE v.recipe_id = ?",
            Long.class,
            r.recipeId());

    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.defaultManualEditRequest(r.optimisticVersion()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentVersion").value(2))
        .andExpect(jsonPath("$.currentVersionBody.versionNumber").value(2))
        .andExpect(jsonPath("$.currentVersionBody.trigger").value("MANUAL_EDIT"))
        .andExpect(jsonPath("$.currentVersionBody.parentVersionId").exists())
        .andExpect(jsonPath("$.optimisticVersion").value(r.optimisticVersion() + 1))
        .andExpect(openApi().isValid(openApiValidator));

    Long versionRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_versions WHERE recipe_id = ?", Long.class, r.recipeId());
    assertThat(versionRows).isEqualTo(2L);

    long ingredientsAfter =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_ingredients i"
                + " JOIN recipe_versions v ON v.id = i.version_id"
                + " WHERE v.recipe_id = ?",
            Long.class,
            r.recipeId());
    long methodAfter =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_method_steps s"
                + " JOIN recipe_versions v ON v.id = s.version_id"
                + " WHERE v.recipe_id = ?",
            Long.class,
            r.recipeId());
    // Append-only: every v1 child row still present, plus a fresh body for v2.
    assertThat(ingredientsAfter).isEqualTo(ingredientsBefore * 2);
    assertThat(methodAfter).isEqualTo(methodBefore * 2);

    String createdByActor =
        jdbcTemplate.queryForObject(
            "SELECT created_by_actor FROM recipe_versions WHERE recipe_id = ? AND version_number = 2",
            String.class,
            r.recipeId());
    assertThat(createdByActor).isEqualTo("user:" + user.userId());

    assertThat(eventCapture.versionEvents()).hasSize(1);
    assertThat(eventCapture.updateEvents()).hasSize(1);
    assertThat(eventCapture.updateEvents().get(0).newVersionNumber()).isEqualTo(2);
  }

  @Test
  void put_returns400_onNoOpEdit() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user);

    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.noopManualEditRequest(r.optimisticVersion()))))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value("https://mealprep.example.com/problems/no-changes"));
  }

  @Test
  void put_returns409_onStaleExpectedOptimisticVersion() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user);

    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.defaultManualEditRequest(r.optimisticVersion() + 99))))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/concurrent-update"));
  }

  @Test
  void put_returns404_whenOwnedByDifferentUser() throws Exception {
    AuthedUser owner = registerUser();
    CreatedRecipe r = createRecipe(owner);
    AuthedUser other = registerUser();

    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId())
                .cookie(other.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.defaultManualEditRequest(r.optimisticVersion()))))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/recipe-not-found"));
  }

  @Test
  void put_returns422_onSystemCatalogue() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user);
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET catalogue = 'SYSTEM' WHERE id = ?", r.recipeId());

    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.defaultManualEditRequest(r.optimisticVersion()))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-catalogue-violation"));
  }

  @Test
  void put_returns400_onValidationError_emptyChangeReason() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user);
    UpdateRecipeManualEditRequest baseline =
        RecipeTestData.defaultManualEditRequest(r.optimisticVersion());
    UpdateRecipeManualEditRequest invalid =
        new UpdateRecipeManualEditRequest(
            baseline.name(),
            baseline.description(),
            baseline.ingredients(),
            baseline.method(),
            baseline.metadata(),
            baseline.tags(),
            "",
            baseline.expectedOptimisticVersion());

    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());
  }

  // ---------------- AFTER_COMMIT capture ----------------

  @TestConfiguration
  static class UpdateEventCaptureConfig {
    @Bean
    UpdateEventCapture updateEventCapture() {
      return new UpdateEventCapture();
    }
  }

  static class UpdateEventCapture {
    private final List<RecipeUpdatedEvent> updateEvents = new CopyOnWriteArrayList<>();
    private final List<RecipeVersionCreatedEvent> versionEvents = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onRecipeUpdated(RecipeUpdatedEvent event) {
      updateEvents.add(event);
    }

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onRecipeVersionCreated(RecipeVersionCreatedEvent event) {
      versionEvents.add(event);
    }

    public List<RecipeUpdatedEvent> updateEvents() {
      return updateEvents;
    }

    public List<RecipeVersionCreatedEvent> versionEvents() {
      return versionEvents;
    }

    public void clear() {
      updateEvents.clear();
      versionEvents.clear();
    }
  }
}
