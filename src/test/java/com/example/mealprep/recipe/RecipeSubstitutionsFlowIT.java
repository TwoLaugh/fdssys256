package com.example.mealprep.recipe;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * End-to-end HTTP flow for the recipe substitutions feature. Covers propose 201, accept, reject,
 * promote-to-version (new RecipeVersion + SUPERSEDED), the active listing, and the
 * with-substitutions overlay read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeSubstitutionsFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM recipe_substitutions");
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
  void create_accept_promote_endToEnd() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    // propose
    MvcResult proposed =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/substitutions")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.defaultSubstitutionRequest(r.versionId()))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("PROPOSED"))
            .andExpect(jsonPath("$.recipeId").value(r.recipeId().toString()))
            .andExpect(jsonPath("$.versionId").value(r.versionId().toString()))
            .andReturn();
    JsonNode subJson = objectMapper.readTree(proposed.getResponse().getContentAsString());
    UUID subId = UUID.fromString(subJson.get("id").asText());
    long subVersion = subJson.get("version").asLong();

    // accept
    MvcResult accepted =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + subId + "/accept")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.acceptRequest(subVersion))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ACCEPTED"))
            .andReturn();
    long acceptedVersion =
        objectMapper.readTree(accepted.getResponse().getContentAsString()).get("version").asLong();

    // list active
    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/substitutions/active").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].state").value("ACCEPTED"));

    // list for version
    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/substitutions")
                .param("versionId", r.versionId().toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    // with-substitutions overlay read
    mvc.perform(
            get("/api/v1/recipes/"
                    + r.recipeId()
                    + "/versions/"
                    + r.versionId()
                    + "/with-substitutions")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appliedSubstitutionIds.length()").value(1));

    // promote-to-version
    MvcResult promoted =
        mvc.perform(
                post("/api/v1/recipes/"
                        + r.recipeId()
                        + "/substitutions/"
                        + subId
                        + "/promote-to-version")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.promoteRequest(
                                acceptedVersion, "Bake the swap into a new version."))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.versionNumber").value(2))
            .andExpect(jsonPath("$.trigger").value("SUBSTITUTION_PROMOTION"))
            .andReturn();
    UUID newVersionId =
        UUID.fromString(
            objectMapper.readTree(promoted.getResponse().getContentAsString()).get("id").asText());

    // substitution is now SUPERSEDED and promotedToVersionId points to the new version
    String state =
        jdbcTemplate.queryForObject(
            "SELECT state FROM recipe_substitutions WHERE id = ?", String.class, subId);
    org.assertj.core.api.Assertions.assertThat(state).isEqualTo("SUPERSEDED");
    String promotedTo =
        jdbcTemplate.queryForObject(
            "SELECT promoted_to_version_id::text FROM recipe_substitutions WHERE id = ?",
            String.class,
            subId);
    org.assertj.core.api.Assertions.assertThat(UUID.fromString(promotedTo)).isEqualTo(newVersionId);
  }

  @Test
  void create_thenReject_setsState_REJECTED() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    MvcResult proposed =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/substitutions")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.defaultSubstitutionRequest(r.versionId()))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode subJson = objectMapper.readTree(proposed.getResponse().getContentAsString());
    UUID subId = UUID.fromString(subJson.get("id").asText());
    long subVersion = subJson.get("version").asLong();

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + subId + "/reject")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.rejectRequest(subVersion, "User changed mind."))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("REJECTED"));
  }

  @Test
  void create_withMissingOriginalIngredient_returns422() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    com.example.mealprep.recipe.api.dto.CreateSubstitutionRequest bad =
        new com.example.mealprep.recipe.api.dto.CreateSubstitutionRequest(
            r.versionId(),
            new com.example.mealprep.recipe.api.dto.SubstitutionItemRequest(
                "not.in.version", new java.math.BigDecimal("1.000"), "g"),
            new com.example.mealprep.recipe.api.dto.SubstitutionItemRequest(
                "soy.crumble", new java.math.BigDecimal("1.000"), "g"),
            com.example.mealprep.recipe.api.dto.SubstitutionReason.DIETARY_TEMP,
            null,
            null,
            null,
            true);

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/substitutions")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://mealprep.example.com/problems/substitution-original-not-in-version"));
  }

  @Test
  void promote_fromProposed_returns422() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    MvcResult proposed =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/substitutions")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.defaultSubstitutionRequest(r.versionId()))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode subJson = objectMapper.readTree(proposed.getResponse().getContentAsString());
    UUID subId = UUID.fromString(subJson.get("id").asText());
    long subVersion = subJson.get("version").asLong();

    mvc.perform(
            post("/api/v1/recipes/"
                    + r.recipeId()
                    + "/substitutions/"
                    + subId
                    + "/promote-to-version")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.promoteRequest(subVersion, "Try promoting before accept."))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://mealprep.example.com/problems/substitution-promotion-precondition"));
  }
}
