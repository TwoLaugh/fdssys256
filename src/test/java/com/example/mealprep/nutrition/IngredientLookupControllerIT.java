package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import com.example.mealprep.nutrition.api.dto.CorrectIngredientMappingRequest;
import com.example.mealprep.nutrition.api.dto.IngredientLookupRequest;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.config.OpenFoodFactsClient;
import com.example.mealprep.nutrition.config.UsdaApiClient;
import com.example.mealprep.nutrition.config.UsdaSearchResultDto;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
 * HTTP flow over the ingredient-mapping cache. USDA + OFF clients are {@link MockBean}s — no real
 * HTTP. Repository persistence is real (Testcontainers Postgres).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class IngredientLookupControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @MockBean private UsdaApiClient usdaApiClient;
  @MockBean private OpenFoodFactsClient openFoodFactsClient;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_ingredient_mapping");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "ingr-" + AuthTestData.shortId();
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

  // ---------------- /lookup ----------------

  @Test
  void lookup_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/nutrition/ingredients/lookup").param("term", "chicken"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void lookup_returns400_whenTermBlank() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/nutrition/ingredients/lookup").param("term", "  ").cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void lookup_coldCache_callsUsda_persistsRow_returns200() throws Exception {
    AuthedUser user = registerUser();
    UsdaSearchResultDto usdaDto =
        new UsdaSearchResultDto(
            List.of(
                new UsdaSearchResultDto.Food(
                    12345,
                    "Chicken Breast",
                    0.9,
                    List.of(
                        new UsdaSearchResultDto.FoodNutrient("Protein", "G", 31.0),
                        new UsdaSearchResultDto.FoodNutrient("Energy", "KCAL", 165.0)))));
    when(usdaApiClient.search("chicken breast")).thenReturn(Optional.of(usdaDto));

    mvc.perform(
            get("/api/v1/nutrition/ingredients/lookup")
                .param("term", "Chicken Breast")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.searchTerm").value("chicken breast"))
        .andExpect(jsonPath("$.source").value("USDA"))
        .andExpect(jsonPath("$.confidence").value(0.85))
        .andExpect(openApi().isValid(openApiValidator));

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_ingredient_mapping WHERE search_term = 'chicken breast'",
            Long.class);
    assertThat(count).isEqualTo(1L);
    verify(openFoodFactsClient, never()).search(anyString());
  }

  @Test
  void lookup_warmCache_returns200_noExternalCall() throws Exception {
    AuthedUser user = registerUser();
    // seed cache directly
    IngredientMapping row =
        NutritionTestData.ingredientMapping("chicken breast", IngredientMappingSource.USDA, 0.85);
    insertIngredient(row);

    mvc.perform(
            get("/api/v1/nutrition/ingredients/lookup")
                .param("term", "Chicken Breast")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.searchTerm").value("chicken breast"));

    verify(usdaApiClient, never()).search(anyString());
    verify(openFoodFactsClient, never()).search(anyString());
  }

  @Test
  void lookup_returns404_whenBothEmpty() throws Exception {
    AuthedUser user = registerUser();
    when(usdaApiClient.search("xyznotreal")).thenReturn(Optional.empty());
    when(openFoodFactsClient.search("xyznotreal")).thenReturn(Optional.empty());

    mvc.perform(
            get("/api/v1/nutrition/ingredients/lookup")
                .param("term", "xyznotreal")
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- /search ----------------

  @Test
  void search_returnsCacheHits_noExternalCall() throws Exception {
    AuthedUser user = registerUser();
    insertIngredient(
        NutritionTestData.ingredientMapping("chicken breast", IngredientMappingSource.USDA, 0.85));
    insertIngredient(
        NutritionTestData.ingredientMapping(
            "chicken thigh", IngredientMappingSource.OPEN_FOOD_FACTS, 0.6));
    insertIngredient(
        NutritionTestData.ingredientMapping("banana", IngredientMappingSource.USDA, 0.8));

    mvc.perform(
            post("/api/v1/nutrition/ingredients/search")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new IngredientLookupRequest("chicken", 10))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cacheOnly").value(true))
        .andExpect(jsonPath("$.hits.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));

    verify(usdaApiClient, never()).search(anyString());
    verify(openFoodFactsClient, never()).search(anyString());
  }

  // ---------------- /correction ----------------

  @Test
  void correction_happyPath_bumpsVersion_setsManual() throws Exception {
    AuthedUser user = registerUser();
    IngredientMapping row =
        NutritionTestData.ingredientMapping("chicken breast", IngredientMappingSource.USDA, 0.6);
    insertIngredient(row);

    CorrectIngredientMappingRequest body =
        new CorrectIngredientMappingRequest(NutritionTestData.defaultNutritionDocument(), 0L);

    mvc.perform(
            put("/api/v1/nutrition/ingredients/chicken%20breast/correction")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("MANUAL"))
        .andExpect(jsonPath("$.confidence").value(1.0))
        .andExpect(jsonPath("$.needsReview").value(false))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void correction_returns404_forUnknownTerm() throws Exception {
    AuthedUser user = registerUser();
    CorrectIngredientMappingRequest body =
        new CorrectIngredientMappingRequest(NutritionTestData.defaultNutritionDocument(), 0L);
    mvc.perform(
            put("/api/v1/nutrition/ingredients/no-such-term/correction")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isNotFound());
  }

  @Test
  void correction_returns409_forStaleVersion() throws Exception {
    AuthedUser user = registerUser();
    IngredientMapping row =
        NutritionTestData.ingredientMapping("chicken breast", IngredientMappingSource.USDA, 0.85);
    insertIngredient(row);

    CorrectIngredientMappingRequest body =
        new CorrectIngredientMappingRequest(NutritionTestData.defaultNutritionDocument(), 999L);

    mvc.perform(
            put("/api/v1/nutrition/ingredients/chicken%20breast/correction")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isConflict());
  }

  // ---------------- /needs-review ----------------

  @Test
  void needsReview_listsOnlyNeedsReviewRows() throws Exception {
    AuthedUser user = registerUser();
    insertIngredient(
        NutritionTestData.ingredientMapping("low conf", IngredientMappingSource.USDA, 0.4));
    insertIngredient(
        NutritionTestData.ingredientMapping("high conf", IngredientMappingSource.USDA, 0.9));

    mvc.perform(get("/api/v1/nutrition/ingredients/needs-review").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].searchTerm").value("low conf"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- helpers ----------------

  /** Direct JDBC insert so tests don't depend on a service method that doesn't exist. */
  private void insertIngredient(IngredientMapping row) throws Exception {
    jdbcTemplate.update(
        "INSERT INTO nutrition_ingredient_mapping (id, search_term, source, external_id,"
            + " nutrition_per_100g, default_piece_grams, confidence, needs_review, last_verified_at,"
            + " version, created_at, updated_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?,"
            + " now(), now())",
        row.getId(),
        row.getSearchTerm(),
        row.getSource().name(),
        row.getExternalId(),
        objectMapper.writeValueAsString(row.getNutritionPer100g()),
        row.getDefaultPieceGrams(),
        row.getConfidence(),
        row.isNeedsReview(),
        row.getLastVerifiedAt(),
        0L);
  }
}
