package com.example.mealprep.recipe;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.recipe.event.ArchiveCause;
import com.example.mealprep.recipe.event.RecipeArchivedEvent;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP flow over {@code POST /archive} / {@code /unarchive} + {@code POST
 * /admin/run-archive-scan}. Also covers the {@code ArchiveEligibilityScanner} end-to-end with a
 * cutoff-driven fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  ArchiveFlowIT.EventCaptureConfig.class
})
@ActiveProfiles("test")
class ArchiveFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private EventCapture eventCapture;

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
    eventCapture.clear();
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

  private UUID createUserRecipe(AuthedUser user) throws Exception {
    MvcResult created =
        mvc.perform(
                post("/api/v1/recipes")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(RecipeTestData.defaultCreateRequest())))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode tree = objectMapper.readTree(created.getResponse().getContentAsString());
    return UUID.fromString(tree.get("id").asText());
  }

  private void demoteToSystemViaJdbc(UUID recipeId) {
    jdbcTemplate.update("UPDATE recipe_recipes SET catalogue = 'SYSTEM' WHERE id = ?", recipeId);
  }

  // ---------------- Archive ----------------

  @Test
  void archive_returns401_whenAnonymous() throws Exception {
    mvc.perform(post("/api/v1/recipes/" + UUID.randomUUID() + "/archive"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void archive_returns404_whenMissing() throws Exception {
    AuthedUser user = registerUser("alice");
    mvc.perform(post("/api/v1/recipes/" + UUID.randomUUID() + "/archive").cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void archive_returns204_andSetsArchivedAt_andPublishesEvent() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/archive").cookie(user.cookie()))
        .andExpect(status().isNoContent());

    Timestamp archivedAt =
        jdbcTemplate.queryForObject(
            "SELECT archived_at FROM recipe_recipes WHERE id = ?", Timestamp.class, recipeId);
    assertThat(archivedAt).isNotNull();

    assertThat(eventCapture.archived()).hasSize(1);
    assertThat(eventCapture.archived().get(0).cause()).isEqualTo(ArchiveCause.MANUAL_ADMIN);
  }

  @Test
  void archive_returns204_idempotent_whenAlreadyArchived() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);
    jdbcTemplate.update("UPDATE recipe_recipes SET archived_at = now() WHERE id = ?", recipeId);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/archive").cookie(user.cookie()))
        .andExpect(status().isNoContent());

    // No new event published on the idempotent path.
    assertThat(eventCapture.archived()).isEmpty();
  }

  @Test
  void archive_returns422_whenDeleted() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);
    jdbcTemplate.update("UPDATE recipe_recipes SET deleted_at = now() WHERE id = ?", recipeId);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/archive").cookie(user.cookie()))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void archive_returns404_whenSomeoneElseUserRecipe() throws Exception {
    AuthedUser alice = registerUser("alice");
    AuthedUser bob = registerUser("bob");
    UUID recipeId = createUserRecipe(alice);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/archive").cookie(bob.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void archive_returns204_onSystemRecipe_evenWhenNotOwner_v1AdminOpenPolicy() throws Exception {
    AuthedUser alice = registerUser("alice");
    AuthedUser bob = registerUser("bob");
    UUID recipeId = createUserRecipe(alice);
    demoteToSystemViaJdbc(recipeId);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/archive").cookie(bob.cookie()))
        .andExpect(status().isNoContent());

    Timestamp archivedAt =
        jdbcTemplate.queryForObject(
            "SELECT archived_at FROM recipe_recipes WHERE id = ?", Timestamp.class, recipeId);
    assertThat(archivedAt).isNotNull();
  }

  // ---------------- Unarchive ----------------

  @Test
  void unarchive_returns204_andClearsArchivedAt_andPublishesNoEvent() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);
    jdbcTemplate.update("UPDATE recipe_recipes SET archived_at = now() WHERE id = ?", recipeId);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/unarchive").cookie(user.cookie()))
        .andExpect(status().isNoContent());

    Timestamp archivedAt =
        jdbcTemplate.queryForObject(
            "SELECT archived_at FROM recipe_recipes WHERE id = ?", Timestamp.class, recipeId);
    assertThat(archivedAt).isNull();

    // LLD has no RecipeUnarchivedEvent — assert no event was published.
    assertThat(eventCapture.archived()).isEmpty();
  }

  @Test
  void unarchive_returns204_idempotent_whenAlreadyUnarchived() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);
    mvc.perform(post("/api/v1/recipes/" + recipeId + "/unarchive").cookie(user.cookie()))
        .andExpect(status().isNoContent());
  }

  // ---------------- Admin: run-archive-scan ----------------

  @Test
  void runArchiveScan_returns401_whenAnonymous() throws Exception {
    mvc.perform(post("/api/v1/recipes/admin/run-archive-scan"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void runArchiveScan_returns200_withFlaggedCount_zero_whenNothingEligible() throws Exception {
    AuthedUser user = registerUser("alice");

    mvc.perform(post("/api/v1/recipes/admin/run-archive-scan").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.flaggedCount").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void runArchiveScan_flagsEligible_systemInactive_butSparesUserAndFreshRecipes() throws Exception {
    AuthedUser user = registerUser("alice");
    // Three recipes: one SYSTEM old (eligible), one SYSTEM fresh (spared), one USER old (spared).
    UUID systemOldId = createUserRecipe(user);
    UUID systemFreshId = createUserRecipe(user);
    UUID userOldId = createUserRecipe(user);

    // Cutoff is 90 days; set lastUsedInPlanAt = 200 days ago / 10 days ago.
    Instant longAgo = Instant.now().minusSeconds(200L * 24 * 60 * 60);
    Instant recent = Instant.now().minusSeconds(10L * 24 * 60 * 60);
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET catalogue = 'SYSTEM', last_used_in_plan_at = ? WHERE id = ?",
        Timestamp.from(longAgo),
        systemOldId);
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET catalogue = 'SYSTEM', last_used_in_plan_at = ? WHERE id = ?",
        Timestamp.from(recent),
        systemFreshId);
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET last_used_in_plan_at = ? WHERE id = ?",
        Timestamp.from(longAgo),
        userOldId);

    MvcResult result =
        mvc.perform(post("/api/v1/recipes/admin/run-archive-scan").cookie(user.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.flaggedCount").value(1))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    // systemOld is now archived; the others are untouched.
    Timestamp systemOldArchived =
        jdbcTemplate.queryForObject(
            "SELECT archived_at FROM recipe_recipes WHERE id = ?", Timestamp.class, systemOldId);
    assertThat(systemOldArchived).isNotNull();
    Timestamp systemFreshArchived =
        jdbcTemplate.queryForObject(
            "SELECT archived_at FROM recipe_recipes WHERE id = ?", Timestamp.class, systemFreshId);
    assertThat(systemFreshArchived).isNull();
    Timestamp userOldArchived =
        jdbcTemplate.queryForObject(
            "SELECT archived_at FROM recipe_recipes WHERE id = ?", Timestamp.class, userOldId);
    assertThat(userOldArchived).isNull();

    // One RecipeArchivedEvent published with cause INACTIVITY_3_MONTHS.
    assertThat(eventCapture.archived()).hasSize(1);
    assertThat(eventCapture.archived().get(0).cause()).isEqualTo(ArchiveCause.INACTIVITY_3_MONTHS);
    assertThat(eventCapture.archived().get(0).recipeId()).isEqualTo(systemOldId);
  }

  @Test
  void runArchiveScan_secondRunReturnsZero_idempotent() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID id = createUserRecipe(user);
    Instant longAgo = Instant.now().minusSeconds(200L * 24 * 60 * 60);
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET catalogue = 'SYSTEM', last_used_in_plan_at = ? WHERE id = ?",
        Timestamp.from(longAgo),
        id);

    mvc.perform(post("/api/v1/recipes/admin/run-archive-scan").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.flaggedCount").value(1));

    eventCapture.clear();

    mvc.perform(post("/api/v1/recipes/admin/run-archive-scan").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.flaggedCount").value(0));

    assertThat(eventCapture.archived()).isEmpty();
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class EventCaptureConfig {
    @Bean
    EventCapture eventCapture() {
      return new EventCapture();
    }
  }

  static class EventCapture {
    private final List<RecipeArchivedEvent> archived = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onArchived(RecipeArchivedEvent event) {
      archived.add(event);
    }

    public List<RecipeArchivedEvent> archived() {
      return archived;
    }

    public void clear() {
      archived.clear();
    }
  }
}
