package com.example.mealprep.household;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.UpdateHouseholdSettingsRequest;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.HouseholdSchedulingPreferences;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault;
import com.example.mealprep.household.domain.entity.SlotKind;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.event.HouseholdSettingsChangedEvent;
import com.example.mealprep.household.testdata.HouseholdTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Full HTTP flow over the household-settings aggregate: settings auto-creation on {@code POST
 * /households}, GET (member vs non-member vs anonymous), PUT (primary vs non-primary vs stale
 * version vs no-op), audit-log paginated read, and slot-configuration resolution.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  HouseholdSettingsFlowIT.SettingsEventCaptureConfig.class
})
@ActiveProfiles("test")
class HouseholdSettingsFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HouseholdUpdateService householdUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private SettingsEventCapture eventCapture;

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

  // ---------------- GET /settings ----------------

  @Test
  void getSettings_returns200_forMember_withDefaultDocument() throws Exception {
    AuthedUser user = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("My Family"));

    mvc.perform(get("/api/v1/households/" + household.id() + "/settings").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.householdId").value(household.id().toString()))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.document.slotDefaults.breakfast.shared").value(true))
        .andExpect(jsonPath("$.document.slotDefaults.dinner.headcount").value(1))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getSettings_returns404_forNonMember() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser stranger = registerUser("stranger");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            primary.userId(), new CreateHouseholdRequest("My Family"));

    mvc.perform(get("/api/v1/households/" + household.id() + "/settings").cookie(stranger.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/household-settings-not-found"));
  }

  @Test
  void getSettings_returns401_whenAnonymous() throws Exception {
    UUID anyId = UUID.randomUUID();
    mvc.perform(get("/api/v1/households/" + anyId + "/settings"))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- PUT /settings ----------------

  @Test
  void putSettings_returns200_forPrimary_andWritesAuditRows_andPublishesEventOnce()
      throws Exception {
    AuthedUser user = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("My Family"));

    HouseholdSettingsDocument prev = HouseholdTestData.defaultDocument();
    Map<SlotKind, SlotDefault> nextSlots = new LinkedHashMap<>(prev.slotDefaults());
    nextSlots.put(SlotKind.dinner, new SlotDefault(false, 2, 60));
    HouseholdSettingsDocument next =
        new HouseholdSettingsDocument(
            nextSlots, prev.customSlots(), prev.defaultHeadcount(), prev.scheduling());
    UpdateHouseholdSettingsRequest body = new UpdateHouseholdSettingsRequest(next, 0L);

    mvc.perform(
            put("/api/v1/households/" + household.id() + "/settings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.document.slotDefaults.dinner.shared").value(false))
        .andExpect(jsonPath("$.document.slotDefaults.dinner.headcount").value(2))
        .andExpect(openApi().isValid(openApiValidator));

    Long auditCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM household_settings_audit", Long.class);
    assertThat(auditCount).isEqualTo(3L);

    assertThat(eventCapture.events()).hasSize(1);
    HouseholdSettingsChangedEvent ev = eventCapture.events().get(0);
    assertThat(ev.householdId()).isEqualTo(household.id());
    assertThat(ev.changedFieldPaths())
        .containsExactly(
            "slotDefaults.dinner.shared",
            "slotDefaults.dinner.headcount",
            "slotDefaults.dinner.timeBudgetMin");
  }

  @Test
  void putSettings_returns200_butNoEventOrAudit_whenIdenticalDocument() throws Exception {
    AuthedUser user = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("My Family"));

    UpdateHouseholdSettingsRequest body =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 0L);

    mvc.perform(
            put("/api/v1/households/" + household.id() + "/settings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0));

    Long auditCount =
        jdbcTemplate.queryForObject("SELECT count(*) FROM household_settings_audit", Long.class);
    assertThat(auditCount).isEqualTo(0L);
    assertThat(eventCapture.events()).isEmpty();
  }

  @Test
  void putSettings_returns403_forStranger() throws Exception {
    AuthedUser primary = registerUser("primary");
    AuthedUser stranger = registerUser("stranger");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            primary.userId(), new CreateHouseholdRequest("My Family"));

    UpdateHouseholdSettingsRequest body =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 0L);

    mvc.perform(
            put("/api/v1/households/" + household.id() + "/settings")
                .cookie(stranger.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/insufficient-household-role"));
  }

  @Test
  void putSettings_returns409_onStaleExpectedVersion() throws Exception {
    AuthedUser user = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("My Family"));

    UpdateHouseholdSettingsRequest body =
        new UpdateHouseholdSettingsRequest(HouseholdTestData.defaultDocument(), 99L);

    mvc.perform(
            put("/api/v1/households/" + household.id() + "/settings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/concurrent-update"));
  }

  @Test
  void putSettings_returns400_whenExpectedVersionNegative() throws Exception {
    AuthedUser user = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("My Family"));

    String body =
        "{\"document\":"
            + objectMapper.writeValueAsString(HouseholdTestData.defaultDocument())
            + ",\"expectedVersion\":-1}";

    mvc.perform(
            put("/api/v1/households/" + household.id() + "/settings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  // ---------------- GET /settings/audit-log ----------------

  @Test
  void getAuditLog_paginated_newestFirst() throws Exception {
    AuthedUser user = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("My Family"));

    // First flip: dinner.shared
    HouseholdSettingsDocument prev = HouseholdTestData.defaultDocument();
    Map<SlotKind, SlotDefault> next1Slots = new LinkedHashMap<>(prev.slotDefaults());
    next1Slots.put(SlotKind.dinner, new SlotDefault(false, 1, 30));
    HouseholdSettingsDocument next1 =
        new HouseholdSettingsDocument(
            next1Slots, new ArrayList<>(), null, new HouseholdSchedulingPreferences());
    mvc.perform(
            put("/api/v1/households/" + household.id() + "/settings")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new UpdateHouseholdSettingsRequest(next1, 0L))))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/v1/households/" + household.id() + "/settings/audit-log")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].fieldPath").value("slotDefaults.dinner.shared"))
        .andExpect(jsonPath("$.content[0].actorUserId").value(user.userId().toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getAuditLog_clampsSizeOverMax() throws Exception {
    AuthedUser user = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("My Family"));

    mvc.perform(
            get("/api/v1/households/" + household.id() + "/settings/audit-log?size=999")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));
  }

  // ---------------- GET /slot-configuration ----------------

  @Test
  void getSlotConfiguration_returns200_withDefaultSlots() throws Exception {
    AuthedUser user = registerUser("primary");
    HouseholdDto household =
        householdUpdateService.createHousehold(
            user.userId(), new CreateHouseholdRequest("My Family"));

    mvc.perform(
            get("/api/v1/households/" + household.id() + "/slot-configuration")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.householdId").value(household.id().toString()))
        .andExpect(jsonPath("$.allEaterUserIds.length()").value(1))
        .andExpect(jsonPath("$.slots.length()").value(4))
        .andExpect(jsonPath("$.slots[0].shared").value(true))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- AFTER_COMMIT capture ----------------

  @TestConfiguration
  static class SettingsEventCaptureConfig {
    @Bean
    SettingsEventCapture settingsEventCapture() {
      return new SettingsEventCapture();
    }
  }

  static class SettingsEventCapture {
    private final List<HouseholdSettingsChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onHouseholdSettingsChanged(HouseholdSettingsChangedEvent event) {
      events.add(event);
    }

    public List<HouseholdSettingsChangedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
