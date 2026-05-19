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
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.config.UrlFetcher;
import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration test for the URL import flow. {@link UrlFetcher} is replaced with a {@code @MockBean}
 * stub so the IT does not hit the real internet; the rest of the stack (parser, persistence,
 * mapping, exception handling) is exercised end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeImportFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;

  /**
   * Response-only validator for the deliberately-malformed negative case below; see {@link
   * com.example.mealprep.testsupport.OpenApiValidatorConfig#responseOnlyOpenApiValidator()}.
   */
  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("responseOnlyOpenApiValidator")
  private OpenApiInteractionValidator responseOnlyOpenApiValidator;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @MockBean private UrlFetcher urlFetcher;

  @BeforeEach
  void resetMocks() {
    Mockito.reset(urlFetcher);
  }

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
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userIdJson =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userIdJson), cookie);
  }

  // ---------------- POST /imports/url ----------------

  @Test
  void importFromUrl_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/recipes/imports/url")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ImportRecipeFromUrlRequest("https://example.com/r", null))))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void importFromUrl_jsonLdFixture_returns201_withBranchesAndProvenance() throws Exception {
    AuthedUser user = registerUser();
    Mockito.when(urlFetcher.fetch("https://example.com/jsonld")).thenReturn(jsonLdHtml());

    MvcResult result =
        mvc.perform(
                post("/api/v1/recipes/imports/url")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new ImportRecipeFromUrlRequest("https://example.com/jsonld", null))))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.dataQuality").value("IMPORTED"))
            .andExpect(jsonPath("$.currentVersionBody.trigger").value("IMPORT"))
            .andExpect(jsonPath("$.branches.length()").value(1))
            .andExpect(jsonPath("$.branches[0].name").value("main"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    UUID recipeId =
        UUID.fromString(
            objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

    String extractionMethod =
        jdbcTemplate.queryForObject(
            "SELECT extraction_method FROM recipe_imports WHERE recipe_id = ?",
            String.class,
            recipeId);
    assertThat(extractionMethod).isEqualTo("json_ld");

    String dataQuality =
        jdbcTemplate.queryForObject(
            "SELECT data_quality FROM recipe_recipes WHERE id = ?", String.class, recipeId);
    assertThat(dataQuality).isEqualTo("IMPORTED");

    String trigger =
        jdbcTemplate.queryForObject(
            "SELECT trigger FROM recipe_versions WHERE recipe_id = ?", String.class, recipeId);
    assertThat(trigger).isEqualTo("IMPORT");
  }

  @Test
  void importFromUrl_unparseableHtml_returns422_withFailureReason() throws Exception {
    AuthedUser user = registerUser();
    Mockito.when(urlFetcher.fetch("https://example.com/none"))
        .thenReturn("<html><body><h1>nothing here</h1></body></html>");

    mvc.perform(
            post("/api/v1/recipes/imports/url")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ImportRecipeFromUrlRequest("https://example.com/none", null))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/recipe-import-failure"))
        .andExpect(jsonPath("$.failureReason").value("no_extractor_matched"))
        .andExpect(openApi().isValid(openApiValidator));

    Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM recipe_recipes", Long.class);
    assertThat(count).isEqualTo(0L);
  }

  @Test
  void importFromUrl_fetchFailure_returns422() throws Exception {
    AuthedUser user = registerUser();
    Mockito.when(urlFetcher.fetch("https://example.com/timeout"))
        .thenThrow(new RecipeImportFailureException("fetch_timeout"));

    mvc.perform(
            post("/api/v1/recipes/imports/url")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ImportRecipeFromUrlRequest("https://example.com/timeout", null))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.failureReason").value("fetch_timeout"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void importFromUrl_blankUrl_returns400() throws Exception {
    AuthedUser user = registerUser();
    // The blank url is intentionally contract-invalid (violates url.minLength) to exercise 400
    // handling, so the request side can never satisfy openApi().isValid(). Validate the response
    // contract only — the 400 ProblemDetail must still conform.
    mvc.perform(
            post("/api/v1/recipes/imports/url")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ImportRecipeFromUrlRequest("", null))))
        .andExpect(status().isBadRequest())
        .andExpect(openApi().isValid(responseOnlyOpenApiValidator));
  }

  // ---------------- GET /import-provenance ----------------

  @Test
  void getImportProvenance_returns200_forImportedRecipe() throws Exception {
    AuthedUser user = registerUser();
    Mockito.when(urlFetcher.fetch("https://example.com/jsonld")).thenReturn(jsonLdHtml());

    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes/imports/url")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new ImportRecipeFromUrlRequest("https://example.com/jsonld", null))))
            .andExpect(status().isCreated())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();
    UUID recipeId =
        UUID.fromString(
            objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

    mvc.perform(get("/api/v1/recipes/" + recipeId + "/import-provenance").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recipeId").value(recipeId.toString()))
        .andExpect(jsonPath("$.sourceType").value("URL"))
        .andExpect(jsonPath("$.sourceUrl").value("https://example.com/jsonld"))
        .andExpect(jsonPath("$.extractionMethod").value("json_ld"))
        .andExpect(jsonPath("$.importedByUserId").value(user.userId().toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getImportProvenance_returns404_forUnknownRecipe() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/recipes/" + UUID.randomUUID() + "/import-provenance")
                .cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/recipe-not-found"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getImportProvenance_returns404_forManualRecipeWithoutImportRow() throws Exception {
    AuthedUser user = registerUser();
    // create a recipe via the manual flow (no import row)
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            com.example.mealprep.recipe.testdata.RecipeTestData
                                .defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();
    UUID recipeId =
        UUID.fromString(
            objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

    mvc.perform(get("/api/v1/recipes/" + recipeId + "/import-provenance").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-import-not-found"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getImportProvenance_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/recipes/" + UUID.randomUUID() + "/import-provenance"))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- helpers ----------------

  private static String jsonLdHtml() {
    return "<!doctype html><html><head><script type=\"application/ld+json\">"
        + "{\"@context\":\"https://schema.org\",\"@type\":\"Recipe\","
        + "\"name\":\"Imported Pasta\","
        + "\"description\":\"Quick weeknight pasta.\","
        + "\"recipeIngredient\":[\"200g spaghetti\",\"1 jar passata\",\"olive oil\"],"
        + "\"recipeInstructions\":[\"Boil pasta.\",\"Heat sauce.\",\"Combine and serve.\"],"
        + "\"prepTime\":\"PT5M\",\"cookTime\":\"PT15M\",\"totalTime\":\"PT20M\","
        + "\"recipeYield\":2,\"recipeCuisine\":\"Italian\"}"
        + "</script></head><body></body></html>";
  }
}
