package com.example.mealprep.recipe;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
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
 * Integration test for recipe-5 version-history endpoints: {@code GET
 * /api/v1/recipes/{id}/versions?branchId=&page=&size=} (paginated list) and {@code GET
 * /api/v1/recipes/{id}/versions/{versionNumber}?branchId=} (single read). Creates a recipe, edits
 * it to produce v2, then exercises the listing + by-number reads against the OpenAPI validator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeVersionHistoryFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private ObjectMapper objectMapper;
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

  @Test
  void list_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            get("/api/v1/recipes/" + UUID.randomUUID() + "/versions")
                .param("branchId", UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void list_returnsAllVersionsNewestFirst_andByNumberReadsEach() throws Exception {
    AuthedUser user = registerUser();
    Created created = createRecipeWithEdit(user);

    // List: two versions, newest (v2) first.
    mvc.perform(
            get("/api/v1/recipes/" + created.recipeId() + "/versions")
                .cookie(user.cookie())
                .param("branchId", created.branchId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].versionNumber").value(2))
        .andExpect(jsonPath("$.content[1].versionNumber").value(1))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(openApi().isValid(openApiValidator));

    // By-number: v1 carries trigger MANUAL_CREATE, v2 carries MANUAL_EDIT.
    mvc.perform(
            get("/api/v1/recipes/" + created.recipeId() + "/versions/1")
                .cookie(user.cookie())
                .param("branchId", created.branchId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNumber").value(1))
        .andExpect(jsonPath("$.trigger").value("MANUAL_CREATE"))
        .andExpect(openApi().isValid(openApiValidator));

    mvc.perform(
            get("/api/v1/recipes/" + created.recipeId() + "/versions/2")
                .cookie(user.cookie())
                .param("branchId", created.branchId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNumber").value(2))
        .andExpect(jsonPath("$.trigger").value("MANUAL_EDIT"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void byNumber_unknownVersion_returns404() throws Exception {
    AuthedUser user = registerUser();
    Created created = createRecipeWithEdit(user);

    mvc.perform(
            get("/api/v1/recipes/" + created.recipeId() + "/versions/99")
                .cookie(user.cookie())
                .param("branchId", created.branchId().toString()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-version-not-found"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void list_unknownBranch_returns404() throws Exception {
    AuthedUser user = registerUser();
    Created created = createRecipeWithEdit(user);

    mvc.perform(
            get("/api/v1/recipes/" + created.recipeId() + "/versions")
                .cookie(user.cookie())
                .param("branchId", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-branch-not-found"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- helpers ----------------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private record Created(UUID recipeId, UUID branchId) {}

  /**
   * Create a recipe then manual-edit it once → branch with v1 (MANUAL_CREATE) + v2 (MANUAL_EDIT).
   */
  private Created createRecipeWithEdit(AuthedUser user) throws Exception {
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode dto = objectMapper.readTree(created.getResponse().getContentAsString());
    UUID recipeId = UUID.fromString(dto.get("id").asText());
    UUID branchId = UUID.fromString(dto.get("currentBranchId").asText());
    long optimisticVersion = dto.get("optimisticVersion").asLong();

    mvc.perform(
            put("/api/v1/recipes/" + recipeId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.defaultManualEditRequest(optimisticVersion))))
        .andExpect(status().isOk())
        .andReturn();

    return new Created(recipeId, branchId);
  }

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
}
