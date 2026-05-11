package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.RecalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
 * HTTP flow over the manual-recalc endpoint. Covers happy-path (200 + Warning header because the
 * Noop writer is wired), anonymous → 401, missing version → 404, body validation → 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class NutritionRecipeRecalcFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private IngredientMappingRepository ingredientMappingRepository;

  @MockBean private RecipeQueryService recipeQueryService;

  // RecipeServiceImpl implements all three of: RecipeQueryService, RecipeUpdateService,
  // RecipeSubstitutionRecorder. @MockBean on RecipeQueryService alone removes the real
  // RecipeServiceImpl bean, leaving Spring unable to wire RecipeUpdateService into
  // RecipeModule. Mock the other two interfaces too so Spring has stubs for everything.
  @MockBean
  private com.example.mealprep.recipe.domain.service.RecipeUpdateService recipeUpdateService;

  @MockBean
  private com.example.mealprep.recipe.spi.RecipeSubstitutionRecorder recipeSubstitutionRecorder;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_ingredient_mapping");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "recalc-" + AuthTestData.shortId();
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

  private RecipeVersionDto versionDto(UUID versionId) {
    IngredientDto ing =
        new IngredientDto(
            UUID.randomUUID(),
            0,
            "chicken breast",
            "Chicken breast",
            BigDecimal.valueOf(1.0),
            "piece",
            null,
            false,
            false,
            BigDecimal.valueOf(0.95));
    return new RecipeVersionDto(
        versionId,
        UUID.randomUUID(),
        2,
        null,
        VersionTrigger.MANUAL_EDIT,
        null,
        null,
        Instant.parse("2026-05-09T10:00:00Z"),
        "user:abc",
        null,
        List.of(ing),
        List.of(),
        null,
        null,
        null);
  }

  @Test
  void recalc_returns401_whenAnonymous() throws Exception {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    RecalculateRecipeNutritionRequest body =
        new RecalculateRecipeNutritionRequest(UUID.randomUUID(), 2);

    mvc.perform(
            post(
                    "/api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate",
                    recipeId,
                    versionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void recalc_returns404_whenVersionMissing() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(recipeQueryService.getVersionWithSubstitutions(recipeId, versionId))
        .thenThrow(new RecipeVersionNotFoundException(versionId));

    RecalculateRecipeNutritionRequest body =
        new RecalculateRecipeNutritionRequest(UUID.randomUUID(), 2);

    mvc.perform(
            post(
                    "/api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate",
                    recipeId,
                    versionId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value(org.hamcrest.Matchers.containsString("recipe-version-lookup-failed")));
  }

  @Test
  void recalc_returns400_whenBranchIdMissing() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    String invalidBody = "{\"versionNumber\": 2}"; // branchId missing

    mvc.perform(
            post(
                    "/api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate",
                    recipeId,
                    versionId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody))
        .andExpect(status().isBadRequest());
  }

  @Test
  void recalc_returns200_withWarningHeader_whenNoopWriterWired() throws Exception {
    AuthedUser user = registerUser();
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    // Seed the cache so the calc resolves.
    IngredientMapping chicken =
        NutritionTestData.ingredientMapping("chicken breast", IngredientMappingSource.USDA, 0.95);
    ingredientMappingRepository.save(chicken);
    when(recipeQueryService.getVersionWithSubstitutions(any(), any()))
        .thenReturn(versionDto(versionId));

    RecalculateRecipeNutritionRequest body =
        new RecalculateRecipeNutritionRequest(UUID.randomUUID(), 2);

    MvcResult result =
        mvc.perform(
                post(
                        "/api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate",
                        recipeId,
                        versionId)
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(header().exists("Warning"))
            .andExpect(jsonPath("$.nutritionStatus").exists())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    String warning = result.getResponse().getHeader("Warning");
    assertThat(warning).contains("recipe-01f impl not yet wired");
  }
}
