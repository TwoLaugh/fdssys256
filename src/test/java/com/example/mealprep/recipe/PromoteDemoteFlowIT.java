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
import com.example.mealprep.recipe.event.RecipePromotedEvent;
import com.example.mealprep.recipe.testdata.RecipeTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
 * Full HTTP flow over {@code POST /promote} and {@code POST /demote}. Per recipe-01g ticket
 * §Promote system → user and §Demote user → system.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  PromoteDemoteFlowIT.EventCaptureConfig.class
})
@ActiveProfiles("test")
class PromoteDemoteFlowIT {

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

  /** Flip an existing USER recipe to SYSTEM at the JDBC layer (skips the full system-seed path). */
  private void demoteToSystemViaJdbc(UUID recipeId) {
    jdbcTemplate.update("UPDATE recipe_recipes SET catalogue = 'SYSTEM' WHERE id = ?", recipeId);
  }

  // ---------------- Promote ----------------

  @Test
  void promote_returns401_whenAnonymous() throws Exception {
    mvc.perform(post("/api/v1/recipes/" + UUID.randomUUID() + "/promote"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void promote_returns404_whenMissing() throws Exception {
    AuthedUser user = registerUser("alice");

    mvc.perform(post("/api/v1/recipes/" + UUID.randomUUID() + "/promote").cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void promote_returns422_whenAlreadyUser() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/promote").cookie(user.cookie()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/recipe-catalogue-violation"));
  }

  @Test
  void promote_returns200_andFlipsSystemToUser_andPublishesEvent() throws Exception {
    AuthedUser owner = registerUser("alice");
    UUID recipeId = createUserRecipe(owner);
    // Now another user promotes the system recipe.
    AuthedUser promoter = registerUser("bob");
    demoteToSystemViaJdbc(recipeId);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/promote").cookie(promoter.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.catalogue").value("USER"))
        .andExpect(jsonPath("$.userId").value(promoter.userId().toString()))
        .andExpect(openApi().isValid(openApiValidator));

    String catalogue =
        jdbcTemplate.queryForObject(
            "SELECT catalogue FROM recipe_recipes WHERE id = ?", String.class, recipeId);
    assertThat(catalogue).isEqualTo("USER");

    assertThat(eventCapture.promoted()).hasSize(1);
    RecipePromotedEvent ev = eventCapture.promoted().get(0);
    assertThat(ev.recipeId()).isEqualTo(recipeId);
    assertThat(ev.userId()).isEqualTo(promoter.userId());
  }

  @Test
  void promote_returns422_whenArchived() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);
    demoteToSystemViaJdbc(recipeId);
    jdbcTemplate.update("UPDATE recipe_recipes SET archived_at = now() WHERE id = ?", recipeId);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/promote").cookie(user.cookie()))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void promote_returns422_whenDeleted() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);
    demoteToSystemViaJdbc(recipeId);
    jdbcTemplate.update("UPDATE recipe_recipes SET deleted_at = now() WHERE id = ?", recipeId);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/promote").cookie(user.cookie()))
        .andExpect(status().isUnprocessableEntity());
  }

  // ---------------- Demote ----------------

  @Test
  void demote_returns401_whenAnonymous() throws Exception {
    mvc.perform(post("/api/v1/recipes/" + UUID.randomUUID() + "/demote"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void demote_returns204_andRetainsUserId_andPublishesEvent() throws Exception {
    AuthedUser user = registerUser("alice");
    UUID recipeId = createUserRecipe(user);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/demote").cookie(user.cookie()))
        .andExpect(status().isNoContent());

    String catalogue =
        jdbcTemplate.queryForObject(
            "SELECT catalogue FROM recipe_recipes WHERE id = ?", String.class, recipeId);
    assertThat(catalogue).isEqualTo("SYSTEM");

    // userId is retained for provenance.
    UUID retainedUserId =
        jdbcTemplate.queryForObject(
            "SELECT user_id FROM recipe_recipes WHERE id = ?", UUID.class, recipeId);
    assertThat(retainedUserId).isEqualTo(user.userId());

    assertThat(eventCapture.archived()).hasSize(1);
    assertThat(eventCapture.archived().get(0).cause()).isEqualTo(ArchiveCause.USER_DEMOTION);
  }

  @Test
  void demote_returns404_whenSomeoneElseRecipe() throws Exception {
    AuthedUser alice = registerUser("alice");
    AuthedUser bob = registerUser("bob");
    UUID recipeId = createUserRecipe(alice);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/demote").cookie(bob.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void demote_returns422_whenAlreadySystem() throws Exception {
    AuthedUser alice = registerUser("alice");
    UUID recipeId = createUserRecipe(alice);
    demoteToSystemViaJdbc(recipeId);

    mvc.perform(post("/api/v1/recipes/" + recipeId + "/demote").cookie(alice.cookie()))
        // System recipe with userId still set returns ownership-OK but catalogue-violation 422.
        .andExpect(status().isUnprocessableEntity());
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
    private final List<RecipePromotedEvent> promoted = new CopyOnWriteArrayList<>();
    private final List<RecipeArchivedEvent> archived = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPromoted(RecipePromotedEvent event) {
      promoted.add(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onArchived(RecipeArchivedEvent event) {
      archived.add(event);
    }

    public List<RecipePromotedEvent> promoted() {
      return promoted;
    }

    public List<RecipeArchivedEvent> archived() {
      return archived;
    }

    public void clear() {
      promoted.clear();
      archived.clear();
    }
  }
}
