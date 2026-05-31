package com.example.mealprep.recipe;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.mealprep.recipe.api.dto.ConfirmImportRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromHtmlRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.config.UrlFetcher;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Integration test for recipe-3 (Paprika-style preview-then-confirm import) and recipe-2 (dedup).
 * {@link UrlFetcher} is a {@code @MockBean} so the preview-url leg does not hit the internet; the
 * rest of the stack (RecipeExtractionService, persistence, dedup, exception handling, OpenAPI
 * contract) is exercised end-to-end. Asserts the new {@code /imports/preview-url}, {@code
 * /imports/preview-html}, {@code /imports/confirm} contract + the 422 duplicate response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeImportPreviewConfirmFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
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

  // ---------------- preview ----------------

  @Test
  void previewUrl_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/recipes/imports/preview-url")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ImportRecipeFromUrlRequest("https://example.com/r", null))))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void previewUrl_jsonLdFixture_returns200_withCandidate_andPersistsNothing() throws Exception {
    AuthedUser user = registerUser();
    Mockito.when(urlFetcher.fetch("https://example.com/jsonld")).thenReturn(jsonLdHtml());

    mvc.perform(
            post("/api/v1/recipes/imports/preview-url")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ImportRecipeFromUrlRequest("https://example.com/jsonld", null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parsedRecipe.name").value("Imported Pasta"))
        .andExpect(jsonPath("$.parsedRecipe.ingredients.length()").value(3))
        .andExpect(jsonPath("$.sourceUrl").value("https://example.com/jsonld"))
        .andExpect(jsonPath("$.extractionMethod").value("json_ld"))
        .andExpect(jsonPath("$.previewToken").exists())
        .andExpect(openApi().isValid(openApiValidator));

    Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM recipe_recipes", Long.class);
    assertThat(count).isEqualTo(0L);
  }

  @Test
  void previewHtml_returns200_withCandidate() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post("/api/v1/recipes/imports/preview-html")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ImportRecipeFromHtmlRequest(
                            "https://example.com/jsonld", jsonLdHtml()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parsedRecipe.name").value("Imported Pasta"))
        .andExpect(jsonPath("$.extractionMethod").value("json_ld"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void previewUrl_unparseableHtml_returns422() throws Exception {
    AuthedUser user = registerUser();
    Mockito.when(urlFetcher.fetch("https://example.com/none"))
        .thenReturn("<html><body><h1>nothing</h1></body></html>");

    mvc.perform(
            post("/api/v1/recipes/imports/preview-url")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ImportRecipeFromUrlRequest("https://example.com/none", null))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.failureReason").value("no_extractor_matched"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- confirm ----------------

  @Test
  void confirm_persistsEditedCandidate_returns201_withImportedDataQuality() throws Exception {
    AuthedUser user = registerUser();
    CreateRecipeRequest edited = RecipeTestData.createRequestWithName("Edited Imported Pasta");
    ConfirmImportRequest body =
        new ConfirmImportRequest(
            "preview-token-abc", "https://example.com/jsonld", "json_ld", edited);

    MvcResult result =
        mvc.perform(
                post("/api/v1/recipes/imports/confirm")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.name").value("Edited Imported Pasta"))
            .andExpect(jsonPath("$.dataQuality").value("IMPORTED"))
            .andExpect(jsonPath("$.currentVersionBody.trigger").value("IMPORT"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    UUID recipeId =
        UUID.fromString(
            objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    String sourceUrl =
        jdbcTemplate.queryForObject(
            "SELECT source_url FROM recipe_imports WHERE recipe_id = ?", String.class, recipeId);
    assertThat(sourceUrl).isEqualTo("https://example.com/jsonld");
  }

  @Test
  void confirm_duplicateOfLibraryRecipe_returns422_withCandidateId() throws Exception {
    AuthedUser user = registerUser();
    // Seed the library: create a recipe (defaultCreateRequest has 3 ingredients + 3 method steps).
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();
    UUID seededId =
        UUID.fromString(
            objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

    // Confirm an import of the identical ingredient set + same method length → dedup hit.
    ConfirmImportRequest body =
        new ConfirmImportRequest(
            null,
            "https://example.com/dup",
            "json_ld",
            RecipeTestData.createRequestWithName("Same Ingredients Different Title"));

    MvcResult dup =
        mvc.perform(
                post("/api/v1/recipes/imports/confirm")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(
                jsonPath("$.type")
                    .value("https://mealprep.example.com/problems/recipe-import-duplicate"))
            .andExpect(jsonPath("$.candidateRecipeId").value(seededId.toString()))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    JsonNode pd = objectMapper.readTree(dup.getResponse().getContentAsString());
    assertThat(pd.get("ingredientOverlap").asDouble()).isEqualTo(1.0);

    // Only the seeded recipe exists; the duplicate was not persisted.
    Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM recipe_recipes", Long.class);
    assertThat(count).isEqualTo(1L);
  }

  // ---------------- create dedup (recipe-2) ----------------

  @Test
  void create_duplicateOfLibraryRecipe_returns422_withCandidateId() throws Exception {
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
    UUID seededId =
        UUID.fromString(
            objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

    mvc.perform(
            post("/api/v1/recipes")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeTestData.createRequestWithName("Dupe by another name"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.candidateRecipeId").value(seededId.toString()))
        .andExpect(openApi().isValid(openApiValidator));
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
