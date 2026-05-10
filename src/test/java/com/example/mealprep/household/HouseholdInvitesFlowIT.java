package com.example.mealprep.household;

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
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.event.HouseholdInviteAcceptedEvent;
import com.example.mealprep.household.event.HouseholdInviteCreatedEvent;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Full HTTP flow over the household-invite aggregate: create / list / revoke / accept paths,
 * including the 410 codes for expired/revoked invites and the AFTER_COMMIT event captures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  HouseholdInvitesFlowIT.InviteEventCaptureConfig.class
})
@ActiveProfiles("test")
class HouseholdInvitesFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HouseholdUpdateService householdUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private InviteEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM household_invite");
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

  // ---------------- POST /current/invites ----------------

  @Test
  void createInvite_byPrimary_returns201_andSurfacesCode_andPublishesEvent() throws Exception {
    AuthedUser primary = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            primary.userId(), new CreateHouseholdRequest("Smith"));

    Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + expiresAt.toString() + "\"}";

    MvcResult result =
        mvc.perform(
                post("/api/v1/households/current/invites")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.householdId").value(household.id().toString()))
            .andExpect(jsonPath("$.intendedRole").value("member"))
            .andExpect(jsonPath("$.inviteCode").isString())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    String json = result.getResponse().getContentAsString();
    String inviteCode = objectMapper.readTree(json).get("inviteCode").asText();
    assertThat(inviteCode).hasSize(16);
    String allowed = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    for (int i = 0; i < inviteCode.length(); i++) {
      assertThat(allowed.indexOf(inviteCode.charAt(i)))
          .as("invite code char")
          .isGreaterThanOrEqualTo(0);
    }

    assertThat(eventCapture.created()).hasSize(1);
    assertThat(eventCapture.created().get(0).householdId()).isEqualTo(household.id());
    assertThat(eventCapture.created().get(0).issuedByUserId()).isEqualTo(primary.userId());
  }

  @Test
  void createInvite_byMember_returns403() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser other = registerUser("other");
    HouseholdDto household =
        householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    // Insert other user as MEMBER directly via JdbcTemplate.
    Instant now = Instant.now();
    jdbcTemplate.update(
        "INSERT INTO household_member (id, household_id, user_id, role, priority, joined_at,"
            + " version, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'member', 100, ?, 0, ?, ?)",
        UUID.randomUUID(),
        household.id(),
        other.userId(),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));

    Instant expiresAt = Instant.now().plus(1, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + expiresAt.toString() + "\"}";
    mvc.perform(
            post("/api/v1/households/current/invites")
                .cookie(other.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/insufficient-household-role"));
  }

  @Test
  void createInvite_byUserNotInAnyHousehold_returns404() throws Exception {
    AuthedUser orphan = registerUser("orphan");
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + expiresAt.toString() + "\"}";
    mvc.perform(
            post("/api/v1/households/current/invites")
                .cookie(orphan.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/household-not-found"));
  }

  @Test
  void createInvite_pastExpiresAt_returns400() throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    Instant pastExpiresAt = Instant.now().minus(1, ChronoUnit.DAYS);
    String body =
        "{\"intendedRole\":\"member\",\"expiresAt\":\"" + pastExpiresAt.toString() + "\"}";
    mvc.perform(
            post("/api/v1/households/current/invites")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  // ---------------- GET /current/invites ----------------

  @Test
  void listPendingInvites_returnsOnlyPendingWithCodeRedacted() throws Exception {
    AuthedUser primary = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));

    // Create two invites via the service; revoke one. Then list should return only the pending one.
    Instant exp = Instant.now().plus(7, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + exp.toString() + "\"}";
    MvcResult firstCreate =
        mvc.perform(
                post("/api/v1/households/current/invites")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String firstId =
        objectMapper.readTree(firstCreate.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(
            post("/api/v1/households/current/invites")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    mvc.perform(delete("/api/v1/households/current/invites/" + firstId).cookie(primary.cookie()))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/households/current/invites").cookie(primary.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].inviteCode").doesNotExist())
        .andExpect(jsonPath("$[0].householdId").value(household.id().toString()))
        .andExpect(jsonPath("$[0].status").value("PENDING"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- DELETE /current/invites/{id} ----------------

  @Test
  void revokeInvite_byPrimary_returns204() throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    Instant exp = Instant.now().plus(7, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + exp.toString() + "\"}";
    MvcResult create =
        mvc.perform(
                post("/api/v1/households/current/invites")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String inviteId =
        objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(delete("/api/v1/households/current/invites/" + inviteId).cookie(primary.cookie()))
        .andExpect(status().isNoContent());

    // Re-revoking → 409.
    mvc.perform(delete("/api/v1/households/current/invites/" + inviteId).cookie(primary.cookie()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/household-invite-already-accepted"));
  }

  @Test
  void revokeInvite_otherHouseholdsInvite_returns404() throws Exception {
    AuthedUser primaryA = registerUser("primaryA");
    householdUpdateService.createHousehold(primaryA.userId(), new CreateHouseholdRequest("A"));
    AuthedUser primaryB = registerUser("primaryB");
    householdUpdateService.createHousehold(primaryB.userId(), new CreateHouseholdRequest("B"));
    Instant exp = Instant.now().plus(7, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + exp.toString() + "\"}";
    MvcResult createA =
        mvc.perform(
                post("/api/v1/households/current/invites")
                    .cookie(primaryA.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String inviteId =
        objectMapper.readTree(createA.getResponse().getContentAsString()).get("id").asText();

    // primaryB tries to revoke A's invite → 404 (don't leak existence).
    mvc.perform(delete("/api/v1/households/current/invites/" + inviteId).cookie(primaryB.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/household-invite-not-found"));
  }

  // ---------------- POST /api/v1/invites/accept ----------------

  @Test
  void acceptInvite_happyPath_returns200_andCreatesMember_andPublishesEvent() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser invitee = registerUser("invitee");
    HouseholdDto household =
        householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    Instant exp = Instant.now().plus(7, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + exp.toString() + "\"}";
    MvcResult create =
        mvc.perform(
                post("/api/v1/households/current/invites")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String code =
        objectMapper.readTree(create.getResponse().getContentAsString()).get("inviteCode").asText();
    eventCapture.clear();

    mvc.perform(
            post("/api/v1/invites/accept")
                .cookie(invitee.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + code + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(invitee.userId().toString()))
        .andExpect(jsonPath("$.role").value("member"))
        .andExpect(jsonPath("$.priority").value(100))
        .andExpect(jsonPath("$.householdId").value(household.id().toString()))
        .andExpect(openApi().isValid(openApiValidator));

    Long memberCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_member WHERE user_id = ?",
            Long.class,
            invitee.userId());
    assertThat(memberCount).isEqualTo(1L);

    Instant acceptedAt =
        jdbcTemplate.queryForObject(
            "SELECT accepted_at FROM household_invite WHERE invite_code = ?",
            (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
            code);
    assertThat(acceptedAt).isNotNull();

    assertThat(eventCapture.accepted()).hasSize(1);
    assertThat(eventCapture.accepted().get(0).acceptedByUserId()).isEqualTo(invitee.userId());
  }

  @Test
  void acceptInvite_unknownCode_returns404() throws Exception {
    AuthedUser invitee = registerUser("invitee");
    mvc.perform(
            post("/api/v1/invites/accept")
                .cookie(invitee.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"ZZZZZZZZZZZZZZZZ\"}"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/household-invite-not-found"));
  }

  @Test
  void acceptInvite_revokedCode_returns410() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser invitee = registerUser("invitee");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    Instant exp = Instant.now().plus(7, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + exp.toString() + "\"}";
    MvcResult create =
        mvc.perform(
                post("/api/v1/households/current/invites")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String code =
        objectMapper.readTree(create.getResponse().getContentAsString()).get("inviteCode").asText();
    String inviteId =
        objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(delete("/api/v1/households/current/invites/" + inviteId).cookie(primary.cookie()))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/v1/invites/accept")
                .cookie(invitee.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + code + "\"}"))
        .andExpect(status().isGone())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/household-invite-revoked"));
  }

  @Test
  void acceptInvite_alreadyAccepted_returns409() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser invitee = registerUser("invitee");
    AuthedUser invitee2 = registerUser("invitee2");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    Instant exp = Instant.now().plus(7, ChronoUnit.DAYS);
    String body = "{\"intendedRole\":\"member\",\"expiresAt\":\"" + exp.toString() + "\"}";
    MvcResult create =
        mvc.perform(
                post("/api/v1/households/current/invites")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String code =
        objectMapper.readTree(create.getResponse().getContentAsString()).get("inviteCode").asText();
    mvc.perform(
            post("/api/v1/invites/accept")
                .cookie(invitee.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + code + "\"}"))
        .andExpect(status().isOk());

    mvc.perform(
            post("/api/v1/invites/accept")
                .cookie(invitee2.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + code + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/household-invite-already-accepted"));
  }

  @Test
  void acceptInvite_anonymous_returns401() throws Exception {
    mvc.perform(
            post("/api/v1/invites/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"ABCDEFGHJKMNPQRS\"}"))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class InviteEventCaptureConfig {
    @Bean
    InviteEventCapture inviteEventCapture() {
      return new InviteEventCapture();
    }
  }

  static class InviteEventCapture {
    private final List<HouseholdInviteCreatedEvent> created = new CopyOnWriteArrayList<>();
    private final List<HouseholdInviteAcceptedEvent> accepted = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onInviteCreated(HouseholdInviteCreatedEvent event) {
      created.add(event);
    }

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onInviteAccepted(HouseholdInviteAcceptedEvent event) {
      accepted.add(event);
    }

    public List<HouseholdInviteCreatedEvent> created() {
      return created;
    }

    public List<HouseholdInviteAcceptedEvent> accepted() {
      return accepted;
    }

    public void clear() {
      created.clear();
      accepted.clear();
    }
  }
}
