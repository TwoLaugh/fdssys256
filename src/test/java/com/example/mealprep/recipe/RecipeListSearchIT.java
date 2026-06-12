package com.example.mealprep.recipe;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.testdata.RecipeRatingTestData;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * End-to-end HTTP flow for {@code GET /api/v1/recipes} (recipe-list-search ticket): the deferred
 * user-private visibility rule (caller's USER rows + shared SYSTEM rows, two-user assert), the
 * catalogue / namePattern / cuisine / maxTotalTimeMins / minDataQuality / includeArchived filters,
 * soft-delete exclusion under every filter combination, pinned updatedAt-DESC sort + page envelope
 * + size bounds, the folded avgTaste/ratingCount aggregate, and the no-N+1 guarantee
 * (Hibernate-statistics: constant statement count regardless of page row count). Responses are
 * contract-validated via the swagger-request-validator matcher.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RecipeListSearchIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private EntityManagerFactory entityManagerFactory;

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

  // ---------------- harness ----------------

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

  private final AtomicInteger recipeCounter = new AtomicInteger();

  private CreatedRecipe createRecipe(Cookie cookie, CreateRecipeRequest request) throws Exception {
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
    return new CreatedRecipe(
        UUID.fromString(body.get("id").asText()),
        UUID.fromString(body.get("currentVersionBody").get("id").asText()));
  }

  private CreatedRecipe createRecipe(Cookie cookie) throws Exception {
    return createRecipe(
        cookie, RecipeTestData.uniqueCreateRequest("list-" + recipeCounter.incrementAndGet()));
  }

  /** Unique-ingredient create request with a custom name + cuisine/time metadata. */
  private CreateRecipeRequest namedRequest(
      String name, String cuisine, int prepMins, int cookMins, int totalMins) {
    CreateRecipeRequest base =
        RecipeTestData.uniqueCreateRequest("list-" + recipeCounter.incrementAndGet());
    return new CreateRecipeRequest(
        name,
        base.description(),
        base.ingredients(),
        base.method(),
        new CreateRecipeMetadataRequest(
            2,
            prepMins,
            cookMins,
            totalMins,
            List.of(),
            null,
            null,
            true,
            cuisine,
            List.of("DINNER")),
        base.tags());
  }

  private void flipToSystem(UUID recipeId) {
    jdbcTemplate.update("UPDATE recipe_recipes SET catalogue = 'SYSTEM' WHERE id = ?", recipeId);
  }

  private void archive(UUID recipeId) {
    jdbcTemplate.update("UPDATE recipe_recipes SET archived_at = now() WHERE id = ?", recipeId);
  }

  private void softDelete(UUID recipeId) {
    jdbcTemplate.update("UPDATE recipe_recipes SET deleted_at = now() WHERE id = ?", recipeId);
  }

  private void setDataQuality(UUID recipeId, String tier) {
    jdbcTemplate.update("UPDATE recipe_recipes SET data_quality = ? WHERE id = ?", tier, recipeId);
  }

  private void setUpdatedAt(UUID recipeId, Instant updatedAt) {
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET updated_at = ? WHERE id = ?",
        Timestamp.from(updatedAt),
        recipeId);
  }

  private JsonNode listAs(Cookie cookie, String queryString) throws Exception {
    MvcResult result =
        mvc.perform(get("/api/v1/recipes" + queryString).cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private List<UUID> contentIds(JsonNode page) {
    List<UUID> ids = new ArrayList<>();
    page.get("content").forEach(row -> ids.add(UUID.fromString(row.get("id").asText())));
    return ids;
  }

  // ---------------- visibility / privacy ----------------

  @Test
  void callerSeesOwnUserRowsPlusSharedSystem_neverAnotherUsersUserRows() throws Exception {
    AuthedUser alice = registerUser("alice");
    AuthedUser bob = registerUser("bob");
    CreatedRecipe alicePrivate = createRecipe(alice.cookie());
    CreatedRecipe shared = createRecipe(alice.cookie());
    flipToSystem(shared.recipeId());
    CreatedRecipe bobOwn = createRecipe(bob.cookie());

    // catalogue absent → bob's own USER rows + the shared SYSTEM pool; alice's private row never.
    JsonNode both = listAs(bob.cookie(), "");
    assertThat(contentIds(both))
        .containsExactlyInAnyOrder(bobOwn.recipeId(), shared.recipeId())
        .doesNotContain(alicePrivate.recipeId());

    // catalogue=SYSTEM → the shared pool only.
    JsonNode systemOnly = listAs(bob.cookie(), "?catalogue=SYSTEM");
    assertThat(contentIds(systemOnly)).containsExactly(shared.recipeId());

    // catalogue=USER → bob's private library only.
    JsonNode userOnly = listAs(bob.cookie(), "?catalogue=USER");
    assertThat(contentIds(userOnly)).containsExactly(bobOwn.recipeId());

    // And alice still sees her own private row (sanity: the predicate is per-caller, not global).
    assertThat(contentIds(listAs(alice.cookie(), "?catalogue=USER")))
        .containsExactlyInAnyOrder(alicePrivate.recipeId());
  }

  // ---------------- archived / deleted state ----------------

  @Test
  void archivedHiddenByDefault_includableOnDemand_deletedNeverReturned() throws Exception {
    AuthedUser user = registerUser("carol");
    CreatedRecipe active = createRecipe(user.cookie());
    CreatedRecipe archived = createRecipe(user.cookie());
    CreatedRecipe deleted = createRecipe(user.cookie());
    archive(archived.recipeId());
    softDelete(deleted.recipeId());

    assertThat(contentIds(listAs(user.cookie(), ""))).containsExactly(active.recipeId());

    JsonNode withArchived = listAs(user.cookie(), "?includeArchived=true");
    assertThat(contentIds(withArchived))
        .containsExactlyInAnyOrder(active.recipeId(), archived.recipeId())
        .doesNotContain(deleted.recipeId());

    // deletedAt rows stay invisible under every other filter combination too.
    assertThat(contentIds(listAs(user.cookie(), "?includeArchived=true&catalogue=USER")))
        .doesNotContain(deleted.recipeId());
  }

  // ---------------- data-quality ordinal floor ----------------

  @Test
  void minDataQuality_isAnOrdinalFloor_notEquality() throws Exception {
    AuthedUser user = registerUser("dave");
    CreatedRecipe verified = createRecipe(user.cookie()); // manual create → USER_VERIFIED
    CreatedRecipe imported = createRecipe(user.cookie());
    CreatedRecipe aiGenerated = createRecipe(user.cookie());
    CreatedRecipe webDiscovered = createRecipe(user.cookie());
    setDataQuality(imported.recipeId(), "IMPORTED");
    setDataQuality(aiGenerated.recipeId(), "AI_GENERATED");
    setDataQuality(webDiscovered.recipeId(), "WEB_DISCOVERED");

    // Floor at IMPORTED admits the IMPORTED ≈ AI_GENERATED tie + USER_VERIFIED; excludes
    // WEB_DISCOVERED.
    assertThat(contentIds(listAs(user.cookie(), "?minDataQuality=IMPORTED")))
        .containsExactlyInAnyOrder(
            verified.recipeId(), imported.recipeId(), aiGenerated.recipeId());

    // The tie is symmetric: AI_GENERATED floor yields the same set.
    assertThat(contentIds(listAs(user.cookie(), "?minDataQuality=AI_GENERATED")))
        .containsExactlyInAnyOrder(
            verified.recipeId(), imported.recipeId(), aiGenerated.recipeId());

    assertThat(contentIds(listAs(user.cookie(), "?minDataQuality=USER_VERIFIED")))
        .containsExactly(verified.recipeId());

    // No floor (and the bottom floor) admit everything.
    assertThat(contentIds(listAs(user.cookie(), ""))).hasSize(4);
    assertThat(contentIds(listAs(user.cookie(), "?minDataQuality=WEB_DISCOVERED"))).hasSize(4);
  }

  // ---------------- name / cuisine / time filters ----------------

  @Test
  void namePatternIsCaseInsensitiveSubstring_cuisineExact_timeIsCeiling() throws Exception {
    AuthedUser user = registerUser("erin");
    CreatedRecipe chicken =
        createRecipe(user.cookie(), namedRequest("Chicken Stir Fry", "Thai", 5, 15, 20));
    CreatedRecipe ragu =
        createRecipe(user.cookie(), namedRequest("Beef Ragu", "Italian", 15, 30, 45));

    assertThat(contentIds(listAs(user.cookie(), "?namePattern=chick")))
        .containsExactly(chicken.recipeId());
    assertThat(contentIds(listAs(user.cookie(), "?namePattern=CHICK")))
        .containsExactly(chicken.recipeId());
    assertThat(contentIds(listAs(user.cookie(), "?namePattern=fry")))
        .containsExactly(chicken.recipeId());

    assertThat(contentIds(listAs(user.cookie(), "?cuisine=Thai")))
        .containsExactly(chicken.recipeId());
    assertThat(contentIds(listAs(user.cookie(), "?maxTotalTimeMins=30")))
        .containsExactly(chicken.recipeId());
    assertThat(contentIds(listAs(user.cookie(), "?maxTotalTimeMins=45")))
        .containsExactlyInAnyOrder(chicken.recipeId(), ragu.recipeId());

    // Empty result is an empty 200 page, not a 404.
    JsonNode empty = listAs(user.cookie(), "?cuisine=Italian&maxTotalTimeMins=30");
    assertThat(empty.get("totalElements").asLong()).isZero();
    assertThat(empty.get("content")).isEmpty();
  }

  // ---------------- pagination, sort, bounds ----------------

  @Test
  void paginationEnvelope_sortPinnedUpdatedAtDesc_andSizeBounds() throws Exception {
    AuthedUser user = registerUser("fred");
    CreatedRecipe oldest = createRecipe(user.cookie());
    CreatedRecipe middle = createRecipe(user.cookie());
    CreatedRecipe newest = createRecipe(user.cookie());
    Instant base = Instant.parse("2026-06-01T12:00:00Z");
    setUpdatedAt(oldest.recipeId(), base);
    setUpdatedAt(middle.recipeId(), base.plusSeconds(60));
    setUpdatedAt(newest.recipeId(), base.plusSeconds(120));

    JsonNode firstPage = listAs(user.cookie(), "?page=0&size=2");
    assertThat(contentIds(firstPage)).containsExactly(newest.recipeId(), middle.recipeId());
    assertThat(firstPage.get("totalElements").asLong()).isEqualTo(3);
    assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
    assertThat(firstPage.get("number").asInt()).isZero();
    assertThat(firstPage.get("size").asInt()).isEqualTo(2);

    JsonNode secondPage = listAs(user.cookie(), "?page=1&size=2");
    assertThat(contentIds(secondPage)).containsExactly(oldest.recipeId());

    // infra-01b bounds: size > 100 → 400; the max itself is accepted.
    mvc.perform(get("/api/v1/recipes?size=101").cookie(user.cookie()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/recipes?size=100").cookie(user.cookie())).andExpect(status().isOk());
    // Invalid enum → 400.
    mvc.perform(get("/api/v1/recipes?catalogue=BOGUS").cookie(user.cookie()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/recipes?maxTotalTimeMins=-1").cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }

  // ---------------- rating aggregate fold ----------------

  @Test
  void listRowsCarryBatchedRatingAggregate_nullAndZeroWhenUnrated() throws Exception {
    AuthedUser user = registerUser("gina");
    CreatedRecipe rated = createRecipe(user.cookie());
    CreatedRecipe unrated = createRecipe(user.cookie());
    mvc.perform(
            post("/api/v1/recipes/" + rated.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.oneTapCreateRequest(rated.versionId(), 80))))
        .andExpect(status().isCreated());

    JsonNode page = listAs(user.cookie(), "");
    JsonNode ratedRow = rowById(page, rated.recipeId());
    JsonNode unratedRow = rowById(page, unrated.recipeId());

    assertThat(ratedRow.get("avgTaste").asDouble()).isEqualTo(80.0);
    assertThat(ratedRow.get("ratingCount").asLong()).isEqualTo(1L);
    assertThat(unratedRow.get("avgTaste").isNull()).isTrue();
    assertThat(unratedRow.get("ratingCount").asLong()).isZero();

    // The non-list read keeps the fields unpopulated (additive-DTO rule: list-only aggregate).
    MvcResult byId =
        mvc.perform(get("/api/v1/recipes/" + rated.recipeId()).cookie(user.cookie()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode detail = objectMapper.readTree(byId.getResponse().getContentAsString());
    assertThat(detail.get("avgTaste").isNull()).isTrue();
    assertThat(detail.get("ratingCount").isNull()).isTrue();
  }

  private JsonNode rowById(JsonNode page, UUID recipeId) {
    for (JsonNode row : page.get("content")) {
      if (row.get("id").asText().equals(recipeId.toString())) {
        return row;
      }
    }
    throw new AssertionError("row " + recipeId + " not in page");
  }

  // ---------------- N+1 guard ----------------

  @Test
  void queryCountIsConstantRegardlessOfPageRowCount_noPerRowN1() throws Exception {
    AuthedUser user = registerUser("hank");
    for (int i = 0; i < 5; i++) {
      CreatedRecipe r = createRecipe(user.cookie());
      // Rate a couple of rows so the aggregate path is exercised, not skipped.
      if (i < 2) {
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeRatingTestData.oneTapCreateRequest(r.versionId(), 70 + i))))
            .andExpect(status().isCreated());
      }
    }
    awaitEmbeddingListenerQuiescence();

    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    long oneRowStatements = measureListStatements(stats, user.cookie(), "?page=0&size=1", 1);
    long fiveRowStatements = measureListStatements(stats, user.cookie(), "?page=0&size=5", 5);

    // The whole read is batched (page query + count + branches + versions + 2 bags + rating
    // aggregate + per-request auth): the statement count must not grow with the row count.
    assertThat(fiveRowStatements)
        .as("JDBC statements for a 5-row page vs a 1-row page (N+1 would scale per row)")
        .isEqualTo(oneRowStatements);
  }

  private long measureListStatements(Statistics stats, Cookie cookie, String query, int expectRows)
      throws Exception {
    stats.clear();
    JsonNode page = listAs(cookie, query);
    assertThat(page.get("content")).hasSize(expectRows);
    return stats.getPrepareStatementCount();
  }

  /**
   * The async embedding listener (AFTER_COMMIT, stub AI) issues its own statements shortly after
   * each create; wait for the terminal status so it cannot race the measured requests.
   */
  private void awaitEmbeddingListenerQuiescence() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      Long pending =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM recipe_versions WHERE embedding_status = 'pending'",
              Long.class);
      if (pending != null && pending == 0L) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("embedding listener still pending after 15s");
  }

  // ---------------- auth ----------------

  @Test
  void unauthenticated_is401() throws Exception {
    mvc.perform(get("/api/v1/recipes")).andExpect(status().isUnauthorized());
  }
}
