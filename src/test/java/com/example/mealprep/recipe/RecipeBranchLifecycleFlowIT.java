package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateBranchRequest;
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
 * End-to-end HTTP flow for the recipe branch-creation feature ({@code POST
 * /api/v1/recipes/{recipeId}/branches} + {@code GET .../branches/{branchId}}). Existing {@code
 * RecipeBranchesFlowIT} only covers the list endpoint; this drives the real {@code
 * RecipeServiceImpl.createBranch} write path (server-derived fingerprint + divergence score,
 * fingerprint-override path) plus every branch-specific 4xx mapped by {@code
 * RecipeExceptionHandler} (reserved name, name conflict, invalid/cross-recipe branch-point,
 * branch-not-found, catalogue-violation). Assertions check persisted state through the real
 * Postgres stack, not just status codes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeBranchLifecycleFlowIT {

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

  // ---------------- POST /branches : happy paths ----------------

  @Test
  void create_returns201_derivesFingerprintAndDivergence_persistsBranchAndV1() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    MvcResult res =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/branches")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.defaultCreateBranchRequest(r.versionId()))))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.name").value("gluten-free-variant"))
            .andExpect(jsonPath("$.recipeId").value(r.recipeId().toString()))
            .andExpect(jsonPath("$.branchPointVersionId").value(r.versionId().toString()))
            .andExpect(jsonPath("$.currentVersion").value(1))
            .andExpect(jsonPath("$.divergenceScore").exists())
            .andReturn();

    UUID branchId =
        UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());

    // The recipe's current branch must NOT have switched (LLD invariant: branch creation is not
    // a checkout) — the auto-created 'main' branch remains current.
    String mainName =
        jdbcTemplate.queryForObject(
            "SELECT b.name FROM recipe_branches b "
                + "JOIN recipe_recipes r ON r.current_branch_id = b.id WHERE r.id = ?",
            String.class,
            r.recipeId());
    assertThat(mainName).isEqualTo("main");

    // A v1 row was written on the new branch with trigger=BRANCH_CREATION + a real fingerprint.
    Long v1Count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM recipe_versions WHERE branch_id = ? "
                + "AND version_number = 1 AND trigger = 'BRANCH_CREATION'",
            Long.class,
            branchId);
    assertThat(v1Count).isEqualTo(1L);

    // FingerprintDeriver actually ran server-side (no override supplied) -> non-null fingerprint.
    String fp =
        jdbcTemplate.queryForObject(
            "SELECT character_fingerprint::text FROM recipe_versions "
                + "WHERE branch_id = ? AND version_number = 1",
            String.class,
            branchId);
    assertThat(fp).isNotNull().contains("Spaghetti");

    // DivergenceScoreCalculator produced a numeric score on the branch row.
    Double divergence =
        jdbcTemplate.queryForObject(
            "SELECT divergence_score FROM recipe_branches WHERE id = ?", Double.class, branchId);
    assertThat(divergence).isNotNull().isBetween(0.0d, 1.0d);
  }

  @Test
  void create_withFingerprintOverride_skipsServerDerivation() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    CharacterFingerprintDto override =
        new CharacterFingerprintDto(
            List.of("chilli", "garlic"),
            List.of(),
            List.of(),
            List.of("spicy"),
            Complexity.MINIMAL,
            "Thai");
    CreateBranchRequest req = RecipeTestData.branchRequestWithOverride(r.versionId(), override);

    MvcResult res =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/branches")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("spicy-variant"))
            .andReturn();

    UUID branchId =
        UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());

    // The override fingerprint was stored verbatim (server derivation skipped).
    String fp =
        jdbcTemplate.queryForObject(
            "SELECT character_fingerprint::text FROM recipe_versions "
                + "WHERE branch_id = ? AND version_number = 1",
            String.class,
            branchId);
    assertThat(fp).contains("chilli").contains("Thai");
  }

  @Test
  void getOne_returns200_forCreatedBranch_and404_forUnknownBranch() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/branches")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeTestData.defaultCreateBranchRequest(r.versionId()))))
            .andExpect(status().isCreated())
            .andReturn();
    UUID branchId =
        UUID.fromString(
            objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/branches/" + branchId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(branchId.toString()))
        .andExpect(jsonPath("$.name").value("gluten-free-variant"));

    mvc.perform(
            get("/api/v1/recipes/" + r.recipeId() + "/branches/" + UUID.randomUUID())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-branch-not-found"));
  }

  // ---------------- POST /branches : 4xx error paths ----------------

  @Test
  void create_returns422_whenNameReserved_main() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    CreateBranchRequest req = RecipeTestData.branchRequestWithName("main", r.versionId());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/branches")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-branch-name-reserved"));
  }

  @Test
  void create_returns409_whenNameConflicts() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/branches")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.branchRequestWithName("keto-variant", r.versionId()))))
        .andExpect(status().isCreated());

    // Second branch with the same name -> 409 recipe-branch-name-conflict.
    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/branches")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.branchRequestWithName("keto-variant", r.versionId()))))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-branch-name-conflict"));
  }

  @Test
  void create_returns422_whenBranchPointVersionUnknown() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    CreateBranchRequest req =
        RecipeTestData.branchRequestWithName("ghost-variant", UUID.randomUUID());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/branches")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-branch-point-invalid"));
  }

  @Test
  void create_returns422_whenBranchPointVersionBelongsToAnotherRecipe() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r1 = createRecipe(user.cookie());
    CreatedRecipe r2 = createRecipe(user.cookie());

    // Branch-point version from r2, but target recipe is r1 -> cross-recipe reject.
    CreateBranchRequest req =
        RecipeTestData.branchRequestWithName("cross-recipe-variant", r2.versionId());

    mvc.perform(
            post("/api/v1/recipes/" + r1.recipeId() + "/branches")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-branch-point-invalid"));
  }

  @Test
  void create_returns404_whenRecipeOwnedByAnotherUser() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    CreatedRecipe r = createRecipe(owner.cookie());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/branches")
                .cookie(intruder.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.defaultCreateBranchRequest(r.versionId()))))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/recipe-not-found"));
  }

  @Test
  void create_returns422_whenRecipeIsSystemCatalogue() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    // Flip the recipe to SYSTEM via JDBC (promotion/demotion endpoints have their own ITs).
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET catalogue = 'SYSTEM' WHERE id = ?", r.recipeId());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/branches")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.defaultCreateBranchRequest(r.versionId()))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-catalogue-violation"));
  }

  @Test
  void create_returns400_whenNameViolatesSlugPattern() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    CreateBranchRequest req =
        RecipeTestData.branchRequestWithName("Has Spaces And Caps", r.versionId());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/branches")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void create_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/recipes/" + UUID.randomUUID() + "/branches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.defaultCreateBranchRequest(UUID.randomUUID()))))
        .andExpect(status().isUnauthorized());
  }
}
