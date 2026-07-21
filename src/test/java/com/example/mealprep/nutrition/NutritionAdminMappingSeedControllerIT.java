package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AdminAccessProperties;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedReport;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedRequest;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.RecipeIngredientLineDto;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.service.NutritionCalculationService;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * G05 seed-path IT against the REAL committed artifact ({@code
 * src/test/resources/graph-seed/ingredient_mapping_seed.json}, generated read-only from the spike
 * at 28599f0). Proves the M-SEED criteria: full-artifact insert, idempotent re-run with unchanged
 * row count, loud collision with never-overwrite, admin gating, seeded-row shape, and an end-to-end
 * recompute over seeded keys returning {@code calculated} with canonical-key micros.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class NutritionAdminMappingSeedControllerIT {

  private static final String SEED_ENDPOINT = "/api/v1/nutrition/admin/ingredient-mappings/seed";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthProperties authProperties;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private IngredientMappingRepository mappingRepository;
  @Autowired private NutritionCalculationService calculationService;
  @Autowired private JdbcTemplate jdbcTemplate;

  // Only the allowlist config record is mocked (default: everyone non-admin ⇒ fail-closed 403).
  // The shared AdminAccessGuard bean is the real production component.
  @MockBean private AdminAccessProperties adminProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_ingredient_mapping");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser register() throws Exception {
    String username = "adm-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userId), cookie);
  }

  private AuthedUser registerAdmin() throws Exception {
    AuthedUser user = register();
    given(adminProperties.isAdmin(user.userId())).willReturn(true);
    return user;
  }

  private static IngredientMappingSeedRequest loadArtifact(ObjectMapper mapper) throws Exception {
    try (var in =
        new ClassPathResource("graph-seed/ingredient_mapping_seed.json").getInputStream()) {
      return mapper.readValue(in, IngredientMappingSeedRequest.class);
    }
  }

  private IngredientMappingSeedReport postSeed(AuthedUser user, Object body, int expectedStatus)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post(SEED_ENDPOINT)
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readValue(
        result.getResponse().getContentAsString(), IngredientMappingSeedReport.class);
  }

  @Test
  void fullArtifactSeed_idempotentRerun_rowShape_andRecompute() throws Exception {
    AuthedUser admin = registerAdmin();
    IngredientMappingSeedRequest artifact = loadArtifact(objectMapper);
    // contract-copy pin (measured 2026-07-20, canonical-name-wins collapse; breaks loudly on
    // canon growth — regenerate the artifact, don't fudge):
    assertThat(artifact.rows()).hasSize(1179);

    // fresh DB → all rows inserted
    IngredientMappingSeedReport first = postSeed(admin, artifact, 200);
    assertThat(first.status()).isEqualTo("OK");
    assertThat(first.inserted()).isEqualTo(artifact.rows().size());
    assertThat(first.skippedIdentical()).isZero();
    assertThat(first.rejected()).isEmpty();
    assertThat(first.collisions()).isEmpty();
    assertThat(first.meta()).isNotNull(); // _meta echoed for audit
    long countAfterFirst = mappingRepository.count();
    assertThat(countAfterFirst).isEqualTo(artifact.rows().size());

    // re-run → all skippedIdentical, row count unchanged (idempotency — M-SEED criterion)
    IngredientMappingSeedReport second = postSeed(admin, artifact, 200);
    assertThat(second.status()).isEqualTo("OK");
    assertThat(second.inserted()).isZero();
    assertThat(second.skippedIdentical()).isEqualTo(artifact.rows().size());
    assertThat(second.collisions()).isEmpty();
    assertThat(mappingRepository.count()).isEqualTo(countAfterFirst);

    // seeded row shape spot-check (guards the micros-vs-vitamins trap)
    IngredientMapping rice = mappingRepository.findBySearchTerm("rice").orElseThrow();
    assertThat(rice.getSource()).isEqualTo(IngredientMappingSource.USDA);
    assertThat(rice.getExternalId()).isEqualTo("169757");
    assertThat(rice.getConfidence()).isEqualByComparingTo("1.000");
    assertThat(rice.isNeedsReview()).isFalse();
    assertThat(rice.getBasisNote()).startsWith("consumed-basis; spike canon corpus@");
    assertThat(rice.getLastVerifiedAt()).isNotNull();
    IngredientNutritionDocument doc = rice.getNutritionPer100g();
    assertThat(doc.vitamins()).isNullOrEmpty();
    assertThat(doc.micros()).containsKey("saturated_fat_g"); // bridge key present
    assertThat(doc.micros()).containsKey("iron_mg");
    assertThat(doc.calories()).isEqualTo(130);

    // recompute over a synthetic 2-line recipe: seeded rows must be consumable by
    // computeRecipeNutrition and yield status "calculated" with non-zero canonical micros
    RecipeNutritionResultDto recompute =
        calculationService.calculateRecipeNutrition(
            new CalculateRecipeNutritionRequest(
                UUID.randomUUID(),
                List.of(
                    new RecipeIngredientLineDto(
                        "rice", "rice", null, "g", new BigDecimal("180"), null),
                    new RecipeIngredientLineDto(
                        "broccoli", "broccoli", null, "g", new BigDecimal("120"), null)),
                1));
    assertThat(recompute.nutritionStatus()).isEqualTo("calculated");
    assertThat(recompute.caloriesPerServing()).isPositive();
    assertThat(recompute.microsPerServing().get("iron_mg")).isPositive();
    assertThat(recompute.microsPerServing().get("vitamin_c_mg")).isPositive();
    assertThat(recompute.microsPerServing()).containsKey("saturated_fat_g");
    assertThat(recompute.unmapped()).isEmpty();
  }

  @Test
  void collision_reportsFailed409_andNeverOverwrites() throws Exception {
    AuthedUser admin = registerAdmin();
    // Someone resolved "rice" through the live pipeline first (different product/basis).
    IngredientMapping poisoned =
        IngredientMapping.builder()
            .id(UUID.randomUUID())
            .searchTerm("rice")
            .source(IngredientMappingSource.USDA)
            .externalId("2512381")
            .nutritionPer100g(
                new IngredientNutritionDocument(
                    365,
                    new BigDecimal("7.1"),
                    new BigDecimal("80.0"),
                    new BigDecimal("0.7"),
                    new BigDecimal("1.3"),
                    null,
                    null,
                    Map.of("iron_mg", new BigDecimal("4.3")),
                    Map.of()))
            .confidence(new BigDecimal("0.850"))
            .needsReview(false)
            .build();
    mappingRepository.saveAndFlush(poisoned);
    long versionBefore = mappingRepository.findBySearchTerm("rice").orElseThrow().getVersion();

    IngredientMappingSeedRequest artifact = loadArtifact(objectMapper);
    IngredientMappingSeedReport report = postSeed(admin, artifact, 409);
    assertThat(report.status()).isEqualTo("FAILED");
    assertThat(report.collisions()).hasSize(1);
    IngredientMappingSeedReport.SeedCollision collision = report.collisions().get(0);
    assertThat(collision.searchTerm()).isEqualTo("rice");
    assertThat(collision.existingSource()).isEqualTo(IngredientMappingSource.USDA);
    assertThat(collision.existingExternalId()).isEqualTo("2512381");
    assertThat(collision.firstDivergingField()).isEqualTo("externalId");
    assertThat(collision.note()).contains("delete + re-seed");
    // the other rows still seeded (idempotency keeps a failed run resumable)
    assertThat(report.inserted()).isEqualTo(artifact.rows().size() - 1);

    // existing row untouched — same version (no JPA update), same payload
    IngredientMapping after = mappingRepository.findBySearchTerm("rice").orElseThrow();
    assertThat(after.getVersion()).isEqualTo(versionBefore);
    assertThat(after.getExternalId()).isEqualTo("2512381");
    assertThat(after.getConfidence()).isEqualByComparingTo("0.850");
    assertThat(after.getNutritionPer100g().calories()).isEqualTo(365);
    assertThat(after.getBasisNote()).isNull();
  }

  @Test
  void rowLevelRejection_isReportedNotThrown() throws Exception {
    AuthedUser admin = registerAdmin();
    IngredientMappingSeedRequest artifact = loadArtifact(objectMapper);
    var goodRow = artifact.rows().get(0);
    var badRow =
        new com.example.mealprep.nutrition.api.dto.IngredientMappingSeedRow(
            " Not Normalised ",
            goodRow.source(),
            null,
            goodRow.basisNote(),
            goodRow.nutritionPer100g());
    IngredientMappingSeedReport report =
        postSeed(admin, new IngredientMappingSeedRequest(null, List.of(goodRow, badRow)), 200);
    assertThat(report.status()).isEqualTo("OK");
    assertThat(report.inserted()).isEqualTo(1);
    assertThat(report.rejected()).hasSize(1);
    assertThat(report.rejected().get(0).reason()).contains("normal-form");
    assertThat(mappingRepository.count()).isEqualTo(1);
  }

  @Test
  void anonymous_returns401() throws Exception {
    mvc.perform(
            post(SEED_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new IngredientMappingSeedRequest(
                            null, loadArtifact(objectMapper).rows().subList(0, 1)))))
        .andExpect(status().isUnauthorized());
    assertThat(mappingRepository.count()).isZero();
  }

  @Test
  void authenticatedNonAdmin_returns403() throws Exception {
    AuthedUser user = register(); // registered but NOT allowlisted → fail-closed 403
    mvc.perform(
            post(SEED_ENDPOINT)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new IngredientMappingSeedRequest(
                            null, loadArtifact(objectMapper).rows().subList(0, 1)))))
        .andExpect(status().isForbidden());
    assertThat(mappingRepository.count()).isZero();
  }
}
