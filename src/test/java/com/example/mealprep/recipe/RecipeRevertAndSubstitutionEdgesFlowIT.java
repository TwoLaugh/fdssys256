package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * End-to-end HTTP flow for the version-revert endpoint ({@code POST
 * /api/v1/recipes/{recipeId}/versions/revert}) and the substitution-lifecycle edge cases not
 * exercised by {@code RecipeSubstitutionsFlowIT}. The revert happy path drives the real {@code
 * RecipeServiceImpl.revertToVersion} write (clones an earlier version body into a new REVERT
 * version, bumping the branch + recipe pointers) and asserts persisted state. The error scenarios
 * cover the branch-not-found 404, version-not-found 404, no-change 400, and stale-optimistic 409
 * paths plus idempotent / terminal-state substitution actions and the cross-recipe substitution 404
 * — exercising {@code RecipeExceptionHandler} mappings reachable only over the full stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeRevertAndSubstitutionEdgesFlowIT {

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

  /** Recipe at v1 plus its main branch id, current version id, and optimistic version. */
  private record CreatedRecipe(
      UUID recipeId, UUID branchId, UUID versionId, long optimisticVersion) {}

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
        UUID.fromString(body.get("currentBranchId").asText()),
        UUID.fromString(body.get("currentVersionBody").get("id").asText()),
        body.get("optimisticVersion").asLong());
  }

  /** Manual-edit the recipe so it advances to v2 (so a revert to v1 is possible). */
  private long manualEditToV2(AuthedUser user, CreatedRecipe r) throws Exception {
    MvcResult edited =
        mvc.perform(
                put("/api/v1/recipes/" + r.recipeId())
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.defaultManualEditRequest(r.optimisticVersion()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentVersion").value(2))
            .andReturn();
    return objectMapper
        .readTree(edited.getResponse().getContentAsString())
        .get("optimisticVersion")
        .asLong();
  }

  // ---------------- POST /versions/revert : happy path ----------------

  @Test
  void revert_returns200_writesRevertVersionCloningTarget_bumpsPointers() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    long optV2 = manualEditToV2(user, r);

    // Revert the main branch back to version 1; server writes v3 (trigger=REVERT) cloning v1.
    MvcResult res =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/versions/revert")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.revertRequest(r.branchId(), 1, optV2))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.versionNumber").value(3))
            .andExpect(jsonPath("$.trigger").value("REVERT"))
            .andExpect(jsonPath("$.changeReason").value("Reverted to version 1"))
            .andReturn();

    UUID newVersionId =
        UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());

    // v3 row persisted with parent pointing at the previous current version (v2).
    Long v3 =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_versions WHERE id = ? "
                + "AND version_number = 3 AND trigger = 'REVERT'",
            Long.class,
            newVersionId);
    assertThat(v3).isEqualTo(1L);

    // Branch + recipe pointers both advanced to 3 (revert happened on the recipe's current branch).
    Integer branchCur =
        jdbcTemplate.queryForObject(
            "SELECT current_version FROM recipe_branches WHERE id = ?",
            Integer.class,
            r.branchId());
    Integer recipeCur =
        jdbcTemplate.queryForObject(
            "SELECT current_version FROM recipe_recipes WHERE id = ?", Integer.class, r.recipeId());
    assertThat(branchCur).isEqualTo(3);
    assertThat(recipeCur).isEqualTo(3);

    // v3 body equals v1's body: the simmer step is back to 25 minutes (v2 had bumped it to 35).
    Integer v3SimmerDuration =
        jdbcTemplate.queryForObject(
            "SELECT s.duration_minutes FROM recipe_method_steps s "
                + "WHERE s.version_id = ? AND s.step_number = 2",
            Integer.class,
            newVersionId);
    assertThat(v3SimmerDuration).isEqualTo(25);
  }

  // ---------------- POST /versions/revert : 4xx error paths ----------------

  @Test
  void revert_returns404_whenBranchUnknown() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    long optV2 = manualEditToV2(user, r);

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/versions/revert")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.revertRequest(UUID.randomUUID(), 1, optV2))))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-branch-not-found"));
  }

  @Test
  void revert_returns404_whenTargetVersionNumberDoesNotExist() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    long optV2 = manualEditToV2(user, r);

    // Version 9 does not exist on the branch (only v1, v2 do).
    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/versions/revert")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.revertRequest(r.branchId(), 9, optV2))))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-version-not-found"));
  }

  @Test
  void revert_returns400_whenTargetIsAlreadyCurrentVersion() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    long optV2 = manualEditToV2(user, r);

    // The branch's current version is 2; reverting to 2 is a no-op -> 400 no-changes.
    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/versions/revert")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.revertRequest(r.branchId(), 2, optV2))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://mealprep.example.com/problems/no-changes"));
  }

  @Test
  void revert_returns409_whenExpectedOptimisticVersionStale() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    manualEditToV2(user, r);

    // Use a stale optimistic version (the original v1 value, now bumped by the manual edit).
    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/versions/revert")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.revertRequest(r.branchId(), 1, r.optimisticVersion()))))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void revert_returns404_whenRecipeOwnedByAnotherUser() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    CreatedRecipe r = createRecipe(owner.cookie());
    long optV2 = manualEditToV2(owner, r);

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/versions/revert")
                .cookie(intruder.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.revertRequest(r.branchId(), 1, optV2))))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/recipe-not-found"));
  }

  @Test
  void revert_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/recipes/" + UUID.randomUUID() + "/versions/revert")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.revertRequest(UUID.randomUUID(), 1, 1L))))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- Substitution lifecycle edge cases ----------------

  private record SubInfo(UUID recipeId, UUID versionId, UUID subId, long subVersion) {}

  private SubInfo proposeSubstitution(AuthedUser user, CreatedRecipe r) throws Exception {
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
    JsonNode sub = objectMapper.readTree(proposed.getResponse().getContentAsString());
    return new SubInfo(
        r.recipeId(),
        r.versionId(),
        UUID.fromString(sub.get("id").asText()),
        sub.get("version").asLong());
  }

  @Test
  void accept_isIdempotent_whenAlreadyAccepted_noVersionBump() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    SubInfo s = proposeSubstitution(user, r);

    MvcResult firstAccept =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + s.subId() + "/accept")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.acceptRequest(s.subVersion()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ACCEPTED"))
            .andReturn();
    long acceptedVersion =
        objectMapper
            .readTree(firstAccept.getResponse().getContentAsString())
            .get("version")
            .asLong();

    // Re-accept with the (unchanged) accepted version: no-op, still ACCEPTED, no @Version bump.
    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + s.subId() + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(RecipeTestData.acceptRequest(acceptedVersion))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACCEPTED"))
        .andExpect(jsonPath("$.version").value((int) acceptedVersion));

    String state =
        jdbcTemplate.queryForObject(
            "SELECT state FROM recipe_substitutions WHERE id = ?", String.class, s.subId());
    assertThat(state).isEqualTo("ACCEPTED");
  }

  @Test
  void reject_isIdempotent_whenAlreadyRejected() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    SubInfo s = proposeSubstitution(user, r);

    MvcResult firstReject =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + s.subId() + "/reject")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.rejectRequest(s.subVersion(), "Not suitable."))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("REJECTED"))
            .andReturn();
    long rejectedVersion =
        objectMapper
            .readTree(firstReject.getResponse().getContentAsString())
            .get("version")
            .asLong();

    // Re-reject: idempotent no-op, still REJECTED.
    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + s.subId() + "/reject")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.rejectRequest(rejectedVersion, null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("REJECTED"));
  }

  @Test
  void accept_returns409_whenExpectedVersionStale() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    SubInfo s = proposeSubstitution(user, r);

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + s.subId() + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.acceptRequest(s.subVersion() + 99))))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void act_returns404_whenSubstitutionUnknown() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    mvc.perform(
            post("/api/v1/recipes/"
                    + r.recipeId()
                    + "/substitutions/"
                    + UUID.randomUUID()
                    + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RecipeTestData.acceptRequest(0L))))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-substitution-not-found"));
  }

  @Test
  void act_returns400_whenActionUnknown() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    SubInfo s = proposeSubstitution(user, r);

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + s.subId() + "/teleport")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RecipeTestData.acceptRequest(0L))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void act_returns404_whenSubstitutionBelongsToAnotherUsersRecipe() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    CreatedRecipe r = createRecipe(owner.cookie());
    SubInfo s = proposeSubstitution(owner, r);

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + s.subId() + "/accept")
                .cookie(intruder.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(RecipeTestData.acceptRequest(s.subVersion()))))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-substitution-not-found"));
  }

  @Test
  void listForVersion_returnsAcceptedSubstitutions_afterAccept() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());
    SubInfo s = proposeSubstitution(user, r);

    // No accepted subs yet -> empty list (PROPOSED is filtered out).
    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/substitutions")
                .param("versionId", r.versionId().toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/substitutions/" + s.subId() + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(RecipeTestData.acceptRequest(s.subVersion()))))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/substitutions")
                .param("versionId", r.versionId().toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].state").value("ACCEPTED"));
  }
}
