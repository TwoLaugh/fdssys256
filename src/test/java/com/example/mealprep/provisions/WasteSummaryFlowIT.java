package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import com.example.mealprep.provisions.api.dto.WasteReason;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
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
 * Aggregate / list flows over the waste log: summary cost-sum + count-by-reason + top-items
 * ranking, paginated list with date filters, and the 400-path when {@code from > to}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class WasteSummaryFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_waste_log");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
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

  private void postWaste(Cookie cookie, LogWasteRequest body) throws Exception {
    mvc.perform(
            post("/api/v1/provisions/waste")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());
  }

  // ---------------- summary ----------------

  @Test
  void summary_aggregatesCounts_costs_andTopItems() throws Exception {
    AuthedUser user = registerUser();
    LocalDate occurredOn = LocalDate.parse("2026-05-08");

    // 3 celery (expired), 1 cheese (didn't like), 1 bread (made too much)
    postWaste(
        user.cookie(),
        new LogWasteRequest(
            null,
            "celery",
            null,
            null,
            WasteReason.EXPIRED,
            new BigDecimal("1.20"),
            occurredOn,
            null));
    postWaste(
        user.cookie(),
        new LogWasteRequest(
            null,
            "celery",
            null,
            null,
            WasteReason.EXPIRED,
            new BigDecimal("1.30"),
            occurredOn,
            null));
    postWaste(
        user.cookie(),
        new LogWasteRequest(
            null,
            "celery",
            null,
            null,
            WasteReason.EXPIRED,
            new BigDecimal("1.10"),
            occurredOn,
            null));
    postWaste(
        user.cookie(),
        new LogWasteRequest(
            null,
            "cheese",
            null,
            null,
            WasteReason.DIDNT_LIKE,
            new BigDecimal("4.00"),
            occurredOn,
            null));
    postWaste(
        user.cookie(),
        new LogWasteRequest(
            null,
            "bread",
            null,
            null,
            WasteReason.MADE_TOO_MUCH,
            new BigDecimal("2.00"),
            occurredOn,
            null));

    mvc.perform(
            get("/api/v1/provisions/waste/summary?from=2026-04-01&to=2026-06-01")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalEntries").value(5))
        .andExpect(jsonPath("$.totalCostEstimate").value(9.6))
        .andExpect(jsonPath("$.countByReason.EXPIRED").value(3))
        .andExpect(jsonPath("$.countByReason.DIDNT_LIKE").value(1))
        .andExpect(jsonPath("$.countByReason.MADE_TOO_MUCH").value(1))
        .andExpect(jsonPath("$.topItems[0].itemName").value("celery"))
        .andExpect(jsonPath("$.topItems[0].entryCount").value(3))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void summary_returns400_whenFromAfterTo() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/provisions/waste/summary?from=2026-06-01&to=2026-04-01")
                .cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void summary_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/provisions/waste/summary?from=2026-04-01&to=2026-06-01"))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- list ----------------

  @Test
  void list_withDefaultDateRange_returnsRecentEntries() throws Exception {
    AuthedUser user = registerUser();
    LocalDate today = LocalDate.now();
    postWaste(
        user.cookie(),
        new LogWasteRequest(
            null, "celery", null, null, WasteReason.EXPIRED, new BigDecimal("1.20"), today, null));

    mvc.perform(get("/api/v1/provisions/waste").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].itemName").value("celery"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void list_returns400_whenFromAfterTo() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/provisions/waste?from=2026-06-01&to=2026-04-01").cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }
}
