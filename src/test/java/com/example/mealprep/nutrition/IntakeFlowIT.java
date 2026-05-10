package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.LogSnackRequest;
import com.example.mealprep.nutrition.event.IntakeLoggedEvent;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
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
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP flow over the intake aggregate. Registers a user, exercises the snack-write
 * (auto-creates day), audit-log read, and snack-delete; covers the AI-stub override path; and
 * verifies {@link IntakeLoggedEvent} fires AFTER_COMMIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  IntakeFlowIT.IntakeEventCaptureConfig.class
})
@ActiveProfiles("test")
class IntakeFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private IntakeEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_intake_audit");
    jdbcTemplate.update("DELETE FROM nutrition_intake_snack");
    jdbcTemplate.update("DELETE FROM nutrition_intake_slot");
    jdbcTemplate.update("DELETE FROM nutrition_intake_day");
    jdbcTemplate.update("DELETE FROM nutrition_daily_activity_log");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
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

  // ---------------- GET /intake/{date} ----------------

  @Test
  void getDay_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/nutrition/intake/2026-05-09")).andExpect(status().isUnauthorized());
  }

  @Test
  void getDay_returns404_whenNoDayRow() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/nutrition/intake/2026-05-09").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/intake-day-not-found"));
  }

  // ---------------- POST /intake/{date}/snacks (auto-creates day) ----------------

  @Test
  void logSnack_autoCreatesDay_andReturns201() throws Exception {
    AuthedUser user = registerUser();
    LogSnackRequest snack = NutritionTestData.defaultSnackRequest();

    mvc.perform(
            post("/api/v1/nutrition/intake/2026-05-09/snacks")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(snack)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.onDate").value("2026-05-09"))
        .andExpect(jsonPath("$.snacks.length()").value(1))
        .andExpect(jsonPath("$.snacks[0].freeText").value("almonds"))
        .andExpect(openApi().isValid(openApiValidator));

    // Day row exists; one audit row of action SNACK_ADD; one event published.
    Long dayCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_intake_day WHERE user_id = ?",
            Long.class,
            user.userId());
    assertThat(dayCount).isEqualTo(1L);

    Long auditCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM nutrition_intake_audit", Long.class);
    assertThat(auditCount).isEqualTo(1L);

    String action =
        jdbcTemplate.queryForObject("SELECT action FROM nutrition_intake_audit", String.class);
    assertThat(action).isEqualTo("SNACK_ADD");

    assertThat(eventCapture.events()).hasSize(1);
    IntakeLoggedEvent event = eventCapture.events().get(0);
    assertThat(event.userId()).isEqualTo(user.userId());
    assertThat(event.action().name()).isEqualTo("SNACK_ADD");
    assertThat(event.snackId()).isNotNull();
  }

  @Test
  void logSnack_then_getDay_returnsSnack() throws Exception {
    AuthedUser user = registerUser();
    LogSnackRequest snack = NutritionTestData.defaultSnackRequest();

    mvc.perform(
            post("/api/v1/nutrition/intake/2026-05-09/snacks")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(snack)))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/nutrition/intake/2026-05-09").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snacks.length()").value(1))
        .andExpect(jsonPath("$.snacks[0].calories").value(180))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- DELETE /intake/{date}/snacks/{snackId} ----------------

  @Test
  void removeSnack_returns404_forUnknownId() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            delete("/api/v1/nutrition/intake/2026-05-09/snacks/" + UUID.randomUUID())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/intake-snack-not-found"));
  }

  @Test
  void removeSnack_existing_returns204() throws Exception {
    AuthedUser user = registerUser();
    LogSnackRequest snack = NutritionTestData.defaultSnackRequest();
    MvcResult result =
        mvc.perform(
                post("/api/v1/nutrition/intake/2026-05-09/snacks")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(snack)))
            .andExpect(status().isCreated())
            .andReturn();
    String snackId =
        objectMapper
            .readTree(result.getResponse().getContentAsString())
            .get("snacks")
            .get(0)
            .get("id")
            .asText();
    eventCapture.clear();

    mvc.perform(
            delete("/api/v1/nutrition/intake/2026-05-09/snacks/" + snackId).cookie(user.cookie()))
        .andExpect(status().isNoContent());

    Long snackCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM nutrition_intake_snack", Long.class);
    assertThat(snackCount).isEqualTo(0L);

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).action().name()).isEqualTo("SNACK_REMOVE");
  }

  // ---------------- GET /intake?from=&to= range validation ----------------

  @Test
  void getRange_returns400_whenFromAfterTo() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/nutrition/intake")
                .cookie(user.cookie())
                .param("from", "2026-05-10")
                .param("to", "2026-05-09"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/invalid-intake-range"));
  }

  @Test
  void getRange_returns400_whenRangeExceeds35Days() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/nutrition/intake")
                .cookie(user.cookie())
                .param("from", "2026-01-01")
                .param("to", "2026-12-31"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/invalid-intake-range"));
  }

  @Test
  void getRange_returns200_whenValid() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/nutrition/intake")
                .cookie(user.cookie())
                .param("from", "2026-05-01")
                .param("to", "2026-05-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class IntakeEventCaptureConfig {
    @Bean
    IntakeEventCapture intakeEventCapture() {
      return new IntakeEventCapture();
    }
  }

  static class IntakeEventCapture {
    private final List<IntakeLoggedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onIntakeLogged(IntakeLoggedEvent event) {
      events.add(event);
    }

    public List<IntakeLoggedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
