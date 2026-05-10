package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.provisions.event.BudgetChangedEvent;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP flow over the budget aggregate. Exercises GET / PUT (insert + update), version-conflict
 * 409s, currency-change 422s, validation 400s, and the {@link BudgetChangedEvent} AFTER_COMMIT
 * publication.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  BudgetFlowIT.BudgetEventCaptureConfig.class
})
@ActiveProfiles("test")
class BudgetFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private BudgetEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_budget");
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

  private static String body(
      String weeklyTarget,
      String currency,
      String toleranceOver,
      String priceSensitivity,
      Boolean enabled,
      Long expectedVersion) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"weeklyTarget\":").append(weeklyTarget);
    sb.append(",\"currency\":\"").append(currency).append("\"");
    if (toleranceOver != null) {
      sb.append(",\"toleranceOver\":").append(toleranceOver);
    }
    sb.append(",\"priceSensitivity\":\"").append(priceSensitivity).append("\"");
    if (enabled != null) {
      sb.append(",\"enabled\":").append(enabled);
    }
    if (expectedVersion != null) {
      sb.append(",\"expectedVersion\":").append(expectedVersion);
    }
    sb.append("}");
    return sb.toString();
  }

  // ---------------- GET ----------------

  @Test
  void get_returns404_whenNoRow() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/provisions/budget").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/budget-not-found"));
  }

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/provisions/budget")).andExpect(status().isUnauthorized());
  }

  @Test
  void get_returns200_withSpendTrackingExplicitlyNull() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk());

    String response =
        mvc.perform(get("/api/v1/provisions/budget").cookie(user.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(user.userId().toString()))
            .andExpect(jsonPath("$.weeklyTarget").value(75.00))
            .andExpect(jsonPath("$.currency").value("GBP"))
            .andExpect(jsonPath("$.priceSensitivity").value("moderate"))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).contains("\"spendTracking\":null");
  }

  @Test
  void get_responseIncludesSpendTrackingKeyAsExplicitNull() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk());

    String json =
        mvc.perform(get("/api/v1/provisions/budget").cookie(user.cookie()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json).contains("\"spendTracking\":null");
  }

  // ---------------- PUT — insert ----------------

  @Test
  void put_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            put("/api/v1/provisions/budget")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void put_insertPath_returns200_andPublishesEventWithNullPrevious() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.weeklyTarget").value(75.00))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).previousWeeklyTarget()).isNull();
  }

  @Test
  void put_insertPath_acceptsOmittedExpectedVersion_andOmittedToleranceOver() throws Exception {
    AuthedUser user = registerUser();

    String json = "{\"weeklyTarget\":50.00,\"currency\":\"GBP\",\"priceSensitivity\":\"low\"}";
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.toleranceOver").value(0))
        .andExpect(jsonPath("$.enabled").value(true));
  }

  // ---------------- PUT — update ----------------

  @Test
  void put_updatePath_versionMatch_bumpsVersion_andPublishesEvent() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk());
    eventCapture.clear();

    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("80.00", "GBP", "5.00", "high", true, 0L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weeklyTarget").value(80.00))
        .andExpect(jsonPath("$.priceSensitivity").value("high"))
        .andExpect(jsonPath("$.version").value(1));

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).previousWeeklyTarget()).isEqualByComparingTo("75.00");
    assertThat(eventCapture.events().get(0).newWeeklyTarget()).isEqualByComparingTo("80.00");
  }

  @Test
  void put_updatePath_staleVersion_returns409() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk());

    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("80.00", "GBP", "5.00", "moderate", true, 99L)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void put_updatePath_currencyChange_returns422() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk());

    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "EUR", "5.00", "moderate", true, 0L)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/budget-currency-change-rejected"));
  }

  @Test
  void put_rePutIdenticalBody_isANoOp_withNoEventAndNoVersionBump() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk());
    eventCapture.clear();

    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0));

    assertThat(eventCapture.events()).isEmpty();
  }

  @Test
  void put_togglesEnabledFlag() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk());

    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", false, 0L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.weeklyTarget").value(75.00));
  }

  // ---------------- Validation 400s ----------------

  @Test
  void put_returns400_whenWeeklyTargetIsZero() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("0", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void put_returns400_whenCurrencyPatternMismatch() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "gbp", "5.00", "moderate", true, 0L)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void put_returns400_whenPriceSensitivityMissing() throws Exception {
    AuthedUser user = registerUser();
    String json =
        "{\"weeklyTarget\":75.00,\"currency\":\"GBP\",\"toleranceOver\":5.00,\"expectedVersion\":0}";
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest());
  }

  @Test
  void put_returns400_whenToleranceOverNegative() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "-1.00", "moderate", true, 0L)))
        .andExpect(status().isBadRequest());
  }

  // ---------------- DB-level constraints (bypass the service) ----------------

  @Test
  void dbCheck_rejectsDirectInsertWithZeroWeeklyTarget() throws Exception {
    AuthedUser user = registerUser();
    UUID rowId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO provision_budget"
                        + " (id, user_id, weekly_target, currency, tolerance_over,"
                        + " price_sensitivity, enabled, version, created_at, updated_at)"
                        + " VALUES (?, ?, 0, 'GBP', 0, 'moderate', true, 0, now(), now())",
                    rowId,
                    user.userId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void dbCheck_rejectsDirectInsertWithNegativeToleranceOver() throws Exception {
    AuthedUser user = registerUser();
    UUID rowId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO provision_budget"
                        + " (id, user_id, weekly_target, currency, tolerance_over,"
                        + " price_sensitivity, enabled, version, created_at, updated_at)"
                        + " VALUES (?, ?, 75.00, 'GBP', -0.01, 'moderate', true, 0, now(), now())",
                    rowId,
                    user.userId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void dbUnique_rejectsSecondRowForSameUser() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("75.00", "GBP", "5.00", "moderate", true, 0L)))
        .andExpect(status().isOk());

    UUID rowId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO provision_budget"
                        + " (id, user_id, weekly_target, currency, tolerance_over,"
                        + " price_sensitivity, enabled, version, created_at, updated_at)"
                        + " VALUES (?, ?, 75.00, 'GBP', 5.00, 'moderate', true, 0, now(), now())",
                    rowId,
                    user.userId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class BudgetEventCaptureConfig {
    @Bean
    BudgetEventCapture budgetEventCapture() {
      return new BudgetEventCapture();
    }
  }

  static class BudgetEventCapture {
    private final List<BudgetChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onChanged(BudgetChangedEvent event) {
      events.add(event);
    }

    public List<BudgetChangedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
