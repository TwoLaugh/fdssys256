package com.example.mealprep.household;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.event.HouseholdCreatedEvent;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP flow over the household aggregate. Registers a user, exercises {@code POST
 * /api/v1/households} (201, 409, 400, 401) and {@code GET /api/v1/households/current} (200, 404,
 * 401), and asserts the partial unique index on PRIMARY role rejects a second primary at the DB
 * level (bypassing the service via JdbcTemplate).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  HouseholdsFlowIT.HouseholdEventCaptureConfig.class
})
@ActiveProfiles("test")
class HouseholdsFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HouseholdUpdateService householdUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private HouseholdEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM household_settings_audit");
    jdbcTemplate.update("DELETE FROM household_settings");
    jdbcTemplate.update("DELETE FROM household_member");
    jdbcTemplate.update("DELETE FROM household");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
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

  // ---------------- POST /api/v1/households ----------------

  @Test
  void post_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/households")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Smith Family\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void post_returns201_andSeatsCreatorAsPrimaryMember_andPublishesEventOnce() throws Exception {
    AuthedUser user = registerUser();

    MvcResult result =
        mvc.perform(
                post("/api/v1/households")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Smith Family\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.name").value("Smith Family"))
            .andExpect(jsonPath("$.createdByUserId").value(user.userId().toString()))
            .andExpect(jsonPath("$.members.length()").value(1))
            .andExpect(jsonPath("$.members[0].userId").value(user.userId().toString()))
            .andExpect(jsonPath("$.members[0].role").value("primary"))
            .andExpect(jsonPath("$.members[0].priority").value(100))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    String responseBody = result.getResponse().getContentAsString();
    UUID householdId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo("/api/v1/households/" + householdId);

    // DB-side asserts via JdbcTemplate (avoids lazy-load issues outside the service tx).
    Long memberCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_member WHERE user_id = ?", Long.class, user.userId());
    assertThat(memberCount).isEqualTo(1L);
    String role =
        jdbcTemplate.queryForObject(
            "SELECT role FROM household_member WHERE user_id = ?", String.class, user.userId());
    assertThat(role).isEqualTo("primary");

    // The HouseholdCreatedEvent fired exactly once AFTER_COMMIT.
    assertThat(eventCapture.events()).hasSize(1);
    HouseholdCreatedEvent captured = eventCapture.events().get(0);
    assertThat(captured.householdId()).isEqualTo(householdId);
    assertThat(captured.createdByUserId()).isEqualTo(user.userId());
    assertThat(captured.scopeKind()).isEqualTo("household");

    // 01b: createHousehold writes a default settings row in the same transaction.
    Long settingsCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_settings WHERE household_id = ?",
            Long.class,
            householdId);
    assertThat(settingsCount).isEqualTo(1L);
  }

  @Test
  void post_returns409_whenUserAlreadyHasHousehold() throws Exception {
    AuthedUser user = registerUser();

    // First creation succeeds.
    householdUpdateService.createHousehold(user.userId(), new CreateHouseholdRequest("First"));

    // Second attempt should 409 with a user-already-in-household ProblemDetail.
    mvc.perform(
            post("/api/v1/households")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Second\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/user-already-in-household"));

    // No second household row was created.
    Long householdCount = jdbcTemplate.queryForObject("SELECT count(*) FROM household", Long.class);
    assertThat(householdCount).isEqualTo(1L);
  }

  @Test
  void post_returns400_onEmptyName() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(
            post("/api/v1/households")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void post_returns400_onNameTooLong() throws Exception {
    AuthedUser user = registerUser();

    String tooLong = "x".repeat(129);
    mvc.perform(
            post("/api/v1/households")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  // ---------------- GET /api/v1/households/current ----------------

  @Test
  void getCurrent_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/households/current")).andExpect(status().isUnauthorized());
  }

  @Test
  void getCurrent_returns404_whenUserHasNoHousehold() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/households/current").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/household-not-found"));
  }

  @Test
  void getCurrent_returns200_withMembersCollection_whenUserIsMember() throws Exception {
    AuthedUser user = registerUser();
    HouseholdDto created =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("Smith Family"));

    mvc.perform(get("/api/v1/households/current").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(created.id().toString()))
        .andExpect(jsonPath("$.name").value("Smith Family"))
        .andExpect(jsonPath("$.members.length()").value(1))
        .andExpect(jsonPath("$.members[0].userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.members[0].role").value("primary"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- DB partial unique index ----------------

  @Test
  void partialUniqueIndex_rejectsSecondPrimaryForSameHousehold() throws Exception {
    AuthedUser user = registerUser();
    HouseholdDto created =
        householdUpdateService.createHousehold(user.userId(), new CreateHouseholdRequest("X"));

    UUID householdId = created.id();
    UUID otherUserId = UUID.randomUUID();
    Instant now = Instant.now();

    // Direct insert that violates idx_household_member_one_primary (second 'primary' for the same
    // household). The service path enforces single-household-per-user; this proves the DB index
    // backs single-primary-per-household even when the service is bypassed.
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO household_member (id, household_id, user_id, role, priority,"
                        + " joined_at, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'primary', 100, ?, 0, ?, ?)",
                    UUID.randomUUID(),
                    householdId,
                    otherUserId,
                    java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class HouseholdEventCaptureConfig {
    @Bean
    HouseholdEventCapture householdEventCapture() {
      return new HouseholdEventCapture();
    }
  }

  static class HouseholdEventCapture {
    private final List<HouseholdCreatedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onHouseholdCreated(HouseholdCreatedEvent event) {
      events.add(event);
    }

    public List<HouseholdCreatedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
