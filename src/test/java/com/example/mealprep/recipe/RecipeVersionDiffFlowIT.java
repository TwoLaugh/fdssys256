package com.example.mealprep.recipe;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
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
 * Integration test for {@code GET /api/v1/recipes/{recipeId}/versions/{from}/diff/{to}}. Asserts
 * the diff endpoint is a key-value lookup of the persisted {@code change_diff} JSONB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeVersionDiffFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

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

  private record VersionPair(UUID recipeId, UUID v1Id, UUID v2Id) {}

  private VersionPair createRecipeAndEditOnce(AuthedUser user) throws Exception {
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
    UUID recipeId = UUID.fromString(tree.get("id").asText());
    UUID v1Id = UUID.fromString(tree.get("currentVersionBody").get("id").asText());
    long optimistic = tree.get("optimisticVersion").asLong();

    MvcResult edited =
        mvc.perform(
                put("/api/v1/recipes/" + recipeId)
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.defaultManualEditRequest(optimistic))))
            .andExpect(status().isOk())
            .andReturn();
    UUID v2Id =
        UUID.fromString(
            objectMapper
                .readTree(edited.getResponse().getContentAsString())
                .get("currentVersionBody")
                .get("id")
                .asText());
    return new VersionPair(recipeId, v1Id, v2Id);
  }

  @Test
  void diff_returns200_keyValueLookup() throws Exception {
    AuthedUser user = registerUser();
    VersionPair pair = createRecipeAndEditOnce(user);

    String url =
        "/api/v1/recipes/" + pair.recipeId() + "/versions/" + pair.v1Id() + "/diff/" + pair.v2Id();
    MvcResult result =
        mvc.perform(get(url).cookie(user.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fromVersionId").value(pair.v1Id().toString()))
            .andExpect(jsonPath("$.toVersionId").value(pair.v2Id().toString()))
            .andExpect(jsonPath("$.methodChanges").isArray())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    // The persisted change_diff JSONB on v2 must match what we returned.
    String persisted =
        jdbcTemplate.queryForObject(
            "SELECT change_diff::text FROM recipe_versions WHERE id = ?",
            String.class,
            pair.v2Id());
    JsonNode persistedNode = objectMapper.readTree(persisted);
    assertThat(persistedNode.path("methodChanges")).isEqualTo(body.path("methodChanges"));
  }

  @Test
  void diff_returns422_whenNonConsecutive() throws Exception {
    AuthedUser user = registerUser();
    VersionPair pair = createRecipeAndEditOnce(user);
    // Asking for v1 → v1 is non-consecutive (v1.parentVersionId is null, not v1).
    String url =
        "/api/v1/recipes/" + pair.recipeId() + "/versions/" + pair.v1Id() + "/diff/" + pair.v1Id();
    mvc.perform(get(url).cookie(user.cookie()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-diff-not-computed"));
  }

  @Test
  void diff_returns404_whenVersionMissing() throws Exception {
    AuthedUser user = registerUser();
    VersionPair pair = createRecipeAndEditOnce(user);
    String url =
        "/api/v1/recipes/"
            + pair.recipeId()
            + "/versions/"
            + pair.v1Id()
            + "/diff/"
            + UUID.randomUUID();
    mvc.perform(get(url).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-version-not-found"));
  }

  @Test
  void diff_returns404_whenVersionRecipeMismatched() throws Exception {
    AuthedUser user = registerUser();
    VersionPair pair = createRecipeAndEditOnce(user);
    UUID otherRecipe = UUID.randomUUID();
    String url =
        "/api/v1/recipes/" + otherRecipe + "/versions/" + pair.v1Id() + "/diff/" + pair.v2Id();
    mvc.perform(get(url).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-version-not-found"));
  }

  @Test
  void diff_returns401_whenAnonymous() throws Exception {
    AuthedUser user = registerUser();
    VersionPair pair = createRecipeAndEditOnce(user);
    String url =
        "/api/v1/recipes/" + pair.recipeId() + "/versions/" + pair.v1Id() + "/diff/" + pair.v2Id();
    mvc.perform(get(url)).andExpect(status().isUnauthorized());
  }
}
