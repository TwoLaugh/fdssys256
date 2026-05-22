package com.example.mealprep.recipe;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.example.mealprep.recipe.api.dto.CreateRatingRequest;
import com.example.mealprep.recipe.testdata.RecipeRatingTestData;
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
 * End-to-end HTTP flow for recipe-02b ratings: one-tap + detailed POST, the aggregate computation,
 * duplicate 409, the path-vs-body mismatch 400, PUT update + stale 409, DELETE, non-owner 404, and
 * the version + recipe summaries. OpenAPI shape validated via the swagger-validator filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeRatingControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM recipe_ratings");
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

  private AuthedUser registerUser(String prefix) throws Exception {
    String username = prefix + "-" + AuthTestData.shortId();
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

  private record CreatedRecipe(UUID recipeId, UUID versionId) {}

  private CreatedRecipe createRecipe(Cookie cookie) throws Exception {
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
    return new CreatedRecipe(
        UUID.fromString(body.get("id").asText()),
        UUID.fromString(body.get("currentVersionBody").get("id").asText()));
  }

  @Test
  void detailedPost_computesAggregate_andPersists() throws Exception {
    AuthedUser user = registerUser("alice");
    CreatedRecipe r = createRecipe(user.cookie());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.detailedCreateRequest(r.versionId()))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.taste").value(85))
        .andExpect(jsonPath("$.aggregate").value(80))
        .andExpect(jsonPath("$.versionId").value(r.versionId().toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void oneTapPost_aggregateEqualsTaste() throws Exception {
    AuthedUser user = registerUser("bob");
    CreatedRecipe r = createRecipe(user.cookie());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.oneTapCreateRequest(r.versionId(), 72))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.taste").value(72))
        .andExpect(jsonPath("$.aggregate").value(72))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void post_outOfRangeTaste_returns400() throws Exception {
    AuthedUser user = registerUser("carol");
    CreatedRecipe r = createRecipe(user.cookie());

    CreateRatingRequest bad =
        new CreateRatingRequest(r.versionId(), null, 101, null, null, null, null);
    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_duplicateForUserVersion_returns409() throws Exception {
    AuthedUser user = registerUser("dave");
    CreatedRecipe r = createRecipe(user.cookie());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.oneTapCreateRequest(r.versionId(), 80))))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.oneTapCreateRequest(r.versionId(), 90))))
        .andExpect(status().isConflict())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void post_versionFromOtherRecipe_returns400() throws Exception {
    AuthedUser user = registerUser("erin");
    CreatedRecipe a = createRecipe(user.cookie());
    CreatedRecipe b = createRecipe(user.cookie());

    // POST under recipe A with B's versionId -> validation 400.
    mvc.perform(
            post("/api/v1/recipes/" + a.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.oneTapCreateRequest(b.versionId(), 80))))
        .andExpect(status().isBadRequest())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void put_updatesAggregate_thenStaleVersionReturns409() throws Exception {
    AuthedUser user = registerUser("fred");
    CreatedRecipe r = createRecipe(user.cookie());

    MvcResult posted =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeRatingTestData.oneTapCreateRequest(r.versionId(), 50))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode ratingJson = objectMapper.readTree(posted.getResponse().getContentAsString());
    UUID ratingId = UUID.fromString(ratingJson.get("id").asText());
    long ver = ratingJson.get("optimisticVersion").asLong();

    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId() + "/ratings/" + ratingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.updateRequest(r.versionId(), 95, ver))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taste").value(95))
        .andExpect(jsonPath("$.aggregate").value(95))
        .andExpect(openApi().isValid(openApiValidator));

    // Re-PUT with the now-stale original version -> 409.
    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId() + "/ratings/" + ratingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.updateRequest(r.versionId(), 60, ver))))
        .andExpect(status().isConflict());
  }

  @Test
  void delete_removesRating_andNonOwnerSees404() throws Exception {
    AuthedUser owner = registerUser("gail");
    AuthedUser other = registerUser("hank");
    CreatedRecipe r = createRecipe(owner.cookie());

    MvcResult posted =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                    .cookie(owner.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeRatingTestData.oneTapCreateRequest(r.versionId(), 80))))
            .andExpect(status().isCreated())
            .andReturn();
    UUID ratingId =
        UUID.fromString(
            objectMapper.readTree(posted.getResponse().getContentAsString()).get("id").asText());

    // Non-owner delete -> 404 (userId-scoped lookup finds nothing).
    mvc.perform(
            delete("/api/v1/recipes/" + r.recipeId() + "/ratings/" + ratingId)
                .cookie(other.cookie()))
        .andExpect(status().isNotFound());

    // Owner delete -> 204.
    mvc.perform(
            delete("/api/v1/recipes/" + r.recipeId() + "/ratings/" + ratingId)
                .cookie(owner.cookie()))
        .andExpect(status().isNoContent());
  }

  @Test
  void summaries_byVersion_andByRecipe() throws Exception {
    AuthedUser user = registerUser("ivan");
    CreatedRecipe r = createRecipe(user.cookie());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.detailedCreateRequest(r.versionId()))))
        .andExpect(status().isCreated());

    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/ratings/summary")
                .param("versionId", r.versionId().toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.avgTaste").value(85.0))
        .andExpect(openApi().isValid(openApiValidator));

    mvc.perform(get("/api/v1/recipes/" + r.recipeId() + "/ratings/summary").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(openApi().isValid(openApiValidator));

    // list-by-version page
    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/ratings")
                .param("versionId", r.versionId().toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(openApi().isValid(openApiValidator));

    // mine
    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/ratings/mine")
                .param("versionId", r.versionId().toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taste").value(85))
        .andExpect(openApi().isValid(openApiValidator));
  }
}
