package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.recipe.event.RecipeRatingFiredEvent;
import com.example.mealprep.recipe.testdata.RecipeRatingTestData;
import com.example.mealprep.recipe.testdata.RecipeTestData;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Proves {@link RecipeRatingFiredEvent} fires {@code AFTER_COMMIT} on POST and on PUT (recipe-02b
 * §19, C-IMP-009), carries the structured rating, and reports {@link Origin#USER}. Capture listener
 * is {@code @Transactional(REQUIRES_NEW)} per the LifestyleConfigEventPublicationIT precedent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, RecipeRatingFiredEventIT.EventCaptureConfig.class})
@ActiveProfiles("test")
class RecipeRatingFiredEventIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private FiredEventCapture capture;

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
    capture.captured().clear();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "rater-" + AuthTestData.shortId();
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

  @Test
  void post_firesEventAfterCommit() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    mvc.perform(
            post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.detailedCreateRequest(r.versionId()))))
        .andExpect(status().isCreated());

    assertThat(capture.captured()).hasSize(1);
    RecipeRatingFiredEvent e = capture.captured().get(0);
    assertThat(e.userId()).isEqualTo(user.userId());
    assertThat(e.recipeId()).isEqualTo(r.recipeId());
    assertThat(e.versionId()).isEqualTo(r.versionId());
    assertThat(e.taste()).isEqualTo(85);
    assertThat(e.aggregate()).isEqualTo(80);
    assertThat(e.origin()).isEqualTo(Origin.USER);
    assertThat(e.originTrace()).isNull();
  }

  @Test
  void put_firesEventAfterCommit() throws Exception {
    AuthedUser user = registerUser();
    CreatedRecipe r = createRecipe(user.cookie());

    MvcResult posted =
        mvc.perform(
                post("/api/v1/recipes/" + r.recipeId() + "/ratings")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            RecipeRatingTestData.oneTapCreateRequest(r.versionId(), 50))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode json = objectMapper.readTree(posted.getResponse().getContentAsString());
    UUID ratingId = UUID.fromString(json.get("id").asText());
    long ver = json.get("optimisticVersion").asLong();
    capture.captured().clear(); // ignore the POST event; assert only the PUT one

    mvc.perform(
            put("/api/v1/recipes/" + r.recipeId() + "/ratings/" + ratingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RecipeRatingTestData.updateRequest(r.versionId(), 95, ver))))
        .andExpect(status().isOk());

    assertThat(capture.captured()).hasSize(1);
    assertThat(capture.captured().get(0).taste()).isEqualTo(95);
    assertThat(capture.captured().get(0).aggregate()).isEqualTo(95);
  }

  @TestConfiguration
  static class EventCaptureConfig {
    @Bean
    FiredEventCapture firedEventCapture() {
      return new FiredEventCapture();
    }
  }

  static class FiredEventCapture {
    private final List<RecipeRatingFiredEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(RecipeRatingFiredEvent event) {
      events.add(event);
    }

    List<RecipeRatingFiredEvent> captured() {
      return events;
    }
  }
}
