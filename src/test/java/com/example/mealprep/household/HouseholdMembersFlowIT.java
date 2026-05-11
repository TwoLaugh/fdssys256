package com.example.mealprep.household;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.example.mealprep.household.event.HouseholdInviteAcceptedEvent;
import com.example.mealprep.household.event.HouseholdMemberAddedEvent;
import com.example.mealprep.household.event.HouseholdMemberRemovedEvent;
import com.example.mealprep.household.event.HouseholdRoleChangedEvent;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP flow over the member-administration endpoints (01d): add / update / remove /
 * change-role. Asserts AFTER_COMMIT event capture, OpenAPI validity, the 404 ladder
 * (cross-household leak protection), the last-primary guard, and that 01c's accept-invite path
 * still emits {@code HouseholdInviteAcceptedEvent} (NOT {@code HouseholdMemberAddedEvent}) after
 * 01d's shared-helper refactor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  HouseholdMembersFlowIT.MemberEventCaptureConfig.class
})
@ActiveProfiles("test")
class HouseholdMembersFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HouseholdUpdateService householdUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private MemberEventCapture eventCapture;

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

  // ---------------- POST /current/members ----------------

  @Test
  void addMember_byPrimary_returns201_andPublishesEvent_andSetsLocation() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            primary.userId(), new CreateHouseholdRequest("Smith"));

    String body =
        "{\"userId\":\""
            + candidate.userId()
            + "\",\"role\":\"member\",\"priority\":200,\"displayName\":\"Bob\"}";

    MvcResult result =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.householdId").value(household.id().toString()))
            .andExpect(jsonPath("$.userId").value(candidate.userId().toString()))
            .andExpect(jsonPath("$.role").value("member"))
            .andExpect(jsonPath("$.priority").value(200))
            .andExpect(jsonPath("$.displayName").value("Bob"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    String memberId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    assertThat(result.getResponse().getHeader("Location"))
        .endsWith("/api/v1/households/current/members/" + memberId);

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_member WHERE user_id = ?",
            Long.class,
            candidate.userId());
    assertThat(count).isEqualTo(1L);

    assertThat(eventCapture.added()).hasSize(1);
    assertThat(eventCapture.added().get(0).userId()).isEqualTo(candidate.userId());
    assertThat(eventCapture.added().get(0).role().name()).isEqualTo("member");
  }

  @Test
  void addMember_priorityNull_defaultsTo100() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    String body = "{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}";

    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.priority").value(100))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void addMember_byMember_returns403() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser nonPrimary = registerUser("nonp");
    AuthedUser candidate = registerUser("candidate");
    HouseholdDto household =
        householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    Instant now = Instant.now();
    jdbcTemplate.update(
        "INSERT INTO household_member (id, household_id, user_id, role, priority, joined_at,"
            + " version, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'member', 100, ?, 0, ?, ?)",
        UUID.randomUUID(),
        household.id(),
        nonPrimary.userId(),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));

    String body = "{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}";
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(nonPrimary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/insufficient-household-role"));
  }

  @Test
  void addMember_byOrphan_returns404() throws Exception {
    AuthedUser orphan = registerUser("orphan");
    AuthedUser candidate = registerUser("candidate");
    String body = "{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}";
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(orphan.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/household-not-found"));
  }

  @Test
  void addMember_targetAlreadyInAnotherHousehold_returns409() throws Exception {
    AuthedUser primaryA = registerUser("pa");
    AuthedUser primaryB = registerUser("pb");
    householdUpdateService.createHousehold(primaryA.userId(), new CreateHouseholdRequest("A"));
    householdUpdateService.createHousehold(primaryB.userId(), new CreateHouseholdRequest("B"));
    String body = "{\"userId\":\"" + primaryB.userId() + "\",\"role\":\"member\"}";
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(primaryA.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/user-already-in-household"));
  }

  @Test
  void addMember_validationErrors_returns400() throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    // missing userId
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"member\"}"))
        .andExpect(status().isBadRequest());
    // priority below min
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userId\":\""
                        + UUID.randomUUID()
                        + "\",\"role\":\"member\",\"priority\":0}"))
        .andExpect(status().isBadRequest());
    // priority above max
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userId\":\""
                        + UUID.randomUUID()
                        + "\",\"role\":\"member\",\"priority\":1001}"))
        .andExpect(status().isBadRequest());
  }

  // ---------------- PATCH /current/members/{memberId} ----------------

  @Test
  void updateMember_byPrimary_returns200_andBumpsVersion() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));

    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();
    long version =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("version").asLong();

    String body =
        "{\"priority\":250,\"displayName\":\"Alice\",\"expectedVersion\":" + version + "}";
    mvc.perform(
            patch("/api/v1/households/current/members/" + memberId)
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value(250))
        .andExpect(jsonPath("$.displayName").value("Alice"))
        .andExpect(jsonPath("$.version").value((int) version + 1))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void updateMember_noOp_returns200_andDoesNotBumpVersion() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();
    long version =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("version").asLong();

    mvc.perform(
            patch("/api/v1/households/current/members/" + memberId)
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value((int) version));
  }

  @Test
  void updateMember_staleExpectedVersion_returns409() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(
            patch("/api/v1/households/current/members/" + memberId)
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"priority\":250,\"expectedVersion\":99}"))
        .andExpect(status().isConflict());
  }

  @Test
  void updateMember_otherHouseholdsMember_returns404() throws Exception {
    AuthedUser primaryA = registerUser("pa");
    AuthedUser candidateA = registerUser("ca");
    AuthedUser primaryB = registerUser("pb");
    householdUpdateService.createHousehold(primaryA.userId(), new CreateHouseholdRequest("A"));
    householdUpdateService.createHousehold(primaryB.userId(), new CreateHouseholdRequest("B"));
    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primaryA.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidateA.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(
            patch("/api/v1/households/current/members/" + memberId)
                .cookie(primaryB.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"priority\":300,\"expectedVersion\":0}"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/household-member-not-found"));
  }

  // ---------------- DELETE /current/members/{memberId} ----------------

  @Test
  void removeMember_byPrimary_returns204_andPublishesEvent() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();
    eventCapture.clear();

    mvc.perform(delete("/api/v1/households/current/members/" + memberId).cookie(primary.cookie()))
        .andExpect(status().isNoContent());

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_member WHERE user_id = ?",
            Long.class,
            candidate.userId());
    assertThat(count).isEqualTo(0L);

    assertThat(eventCapture.removed()).hasSize(1);
    assertThat(eventCapture.removed().get(0).userId()).isEqualTo(candidate.userId());
    assertThat(eventCapture.removed().get(0).roleAtRemoval().name()).isEqualTo("member");
  }

  @Test
  void removeMember_selfRemoveByMember_returns204() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(delete("/api/v1/households/current/members/" + memberId).cookie(candidate.cookie()))
        .andExpect(status().isNoContent());
  }

  @Test
  void removeMember_lastPrimaryWithOthersPresent_returns409() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    HouseholdDto household =
        householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
        .andExpect(status().isCreated());

    // find primary's memberId
    UUID primaryMemberId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM household_member WHERE user_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject(1),
            primary.userId());

    mvc.perform(
            delete("/api/v1/households/current/members/" + primaryMemberId)
                .cookie(primary.cookie()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/last-primary-removal"));
    assertThat(household.id()).isNotNull(); // anchor capture
  }

  @Test
  void removeMember_onlyMemberPrimary_returns204_andPreservesHousehold() throws Exception {
    AuthedUser primary = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    UUID primaryMemberId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM household_member WHERE user_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject(1),
            primary.userId());

    mvc.perform(
            delete("/api/v1/households/current/members/" + primaryMemberId)
                .cookie(primary.cookie()))
        .andExpect(status().isNoContent());

    Long householdRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household WHERE id = ?", Long.class, household.id());
    assertThat(householdRows).isEqualTo(1L);
    Long memberRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_member WHERE household_id = ?",
            Long.class,
            household.id());
    assertThat(memberRows).isEqualTo(0L);
  }

  @Test
  void removeMember_byMemberTargetingDifferentMember_returns403() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser memberA = registerUser("memberA");
    AuthedUser memberB = registerUser("memberB");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    MvcResult addA =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + memberA.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberAId =
        objectMapper.readTree(addA.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + memberB.userId() + "\",\"role\":\"member\"}"))
        .andExpect(status().isCreated());

    mvc.perform(delete("/api/v1/households/current/members/" + memberAId).cookie(memberB.cookie()))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/insufficient-household-role"));
  }

  // ---------------- POST /current/members/{memberId}/role ----------------

  @Test
  void changeRole_demoteLastPrimary_returns409() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    mvc.perform(
            post("/api/v1/households/current/members")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
        .andExpect(status().isCreated());

    UUID primaryMemberId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM household_member WHERE user_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject(1),
            primary.userId());

    mvc.perform(
            post("/api/v1/households/current/members/" + primaryMemberId + "/role")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newRole\":\"member\",\"expectedVersion\":0}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/last-primary-removal"));
  }

  @Test
  void changeRole_noOp_returns200_andPublishesNoEvent() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();
    long version =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("version").asLong();
    eventCapture.clear();

    mvc.perform(
            post("/api/v1/households/current/members/" + memberId + "/role")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newRole\":\"member\",\"expectedVersion\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value((int) version));
    assertThat(eventCapture.roleChanged()).isEmpty();
  }

  @Test
  void changeRole_byNonPrimary_returns403() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(
            post("/api/v1/households/current/members/" + memberId + "/role")
                .cookie(candidate.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newRole\":\"primary\",\"expectedVersion\":0}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void changeRole_missingNewRole_returns400() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser candidate = registerUser("candidate");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    MvcResult addResult =
        mvc.perform(
                post("/api/v1/households/current/members")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + candidate.userId() + "\",\"role\":\"member\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String memberId =
        objectMapper.readTree(addResult.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(
            post("/api/v1/households/current/members/" + memberId + "/role")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
        .andExpect(status().isBadRequest());
  }

  // ---------------- 01c regression: accept-invite still emits only InviteAcceptedEvent
  // ----------------

  @Test
  void acceptInvite_afterRefactor_emitsOnlyInviteAcceptedEvent() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser invitee = registerUser("invitee");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    String createBody =
        "{\"intendedRole\":\"member\",\"expiresAt\":\"" + Instant.now().plusSeconds(86400) + "\"}";
    MvcResult create =
        mvc.perform(
                post("/api/v1/households/current/invites")
                    .cookie(primary.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
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
        .andExpect(status().isOk());

    assertThat(eventCapture.added()).isEmpty();
    assertThat(eventCapture.accepted()).hasSize(1);
    assertThat(eventCapture.accepted().get(0).acceptedByUserId()).isEqualTo(invitee.userId());
  }

  // ---------------- Anonymous ----------------

  @Test
  void addMember_anonymous_returns401() throws Exception {
    mvc.perform(
            post("/api/v1/households/current/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + UUID.randomUUID() + "\",\"role\":\"member\"}"))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class MemberEventCaptureConfig {
    @Bean
    MemberEventCapture memberEventCapture() {
      return new MemberEventCapture();
    }
  }

  static class MemberEventCapture {
    private final List<HouseholdMemberAddedEvent> added = new CopyOnWriteArrayList<>();
    private final List<HouseholdMemberRemovedEvent> removed = new CopyOnWriteArrayList<>();
    private final List<HouseholdRoleChangedEvent> roleChanged = new CopyOnWriteArrayList<>();
    private final List<HouseholdInviteAcceptedEvent> accepted = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberAdded(HouseholdMemberAddedEvent event) {
      added.add(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberRemoved(HouseholdMemberRemovedEvent event) {
      removed.add(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoleChanged(HouseholdRoleChangedEvent event) {
      roleChanged.add(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInviteAccepted(HouseholdInviteAcceptedEvent event) {
      accepted.add(event);
    }

    List<HouseholdMemberAddedEvent> added() {
      return added;
    }

    List<HouseholdMemberRemovedEvent> removed() {
      return removed;
    }

    List<HouseholdRoleChangedEvent> roleChanged() {
      return roleChanged;
    }

    List<HouseholdInviteAcceptedEvent> accepted() {
      return accepted;
    }

    void clear() {
      added.clear();
      removed.clear();
      roleChanged.clear();
      accepted.clear();
    }
  }
}
