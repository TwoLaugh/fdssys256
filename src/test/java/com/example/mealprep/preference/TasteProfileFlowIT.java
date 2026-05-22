package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest;
import com.example.mealprep.preference.domain.repository.TasteProfileAuditLogRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full HTTP flow over the taste-profile endpoints. Auth → init via service → GET → PUT → audit-log
 * + versions pagination → 404s and 409s.
 *
 * <p>OpenAPI contract validation is intentionally NOT applied here. The taste profile document
 * shape is the largest schema in the API surface (12 nested records); transient swagger-parser
 * nullability quirks have bitten us before on similar shapes. The unit tests + the JSONB round-trip
 * cover the contract; CI's separate OpenAPI-lint job covers the YAML.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class TasteProfileFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private TasteProfileRepository tasteProfileRepository;
  @Autowired private TasteProfileAuditLogRepository auditLogRepository;
  @Autowired private TasteProfileVersionRepository versionRepository;
  @Autowired private TasteProfileUpdateService tasteProfileUpdateService;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    auditLogRepository.deleteAll();
    versionRepository.deleteAll();
    tasteProfileRepository.deleteAll();
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  // ---------------- helpers ----------------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "tasteUser-" + AuthTestData.shortId();
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

  // ---------------- tests ----------------

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/preferences/taste-profile")).andExpect(status().isUnauthorized());
  }

  @Test
  void get_returns404_whenProfileNotInitialised() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/preferences/taste-profile").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/taste-profile-not-found"));
  }

  @Test
  void get_returns200_afterInitialisation() throws Exception {
    AuthedUser user = registerUser();
    TasteProfileDto initialised = tasteProfileUpdateService.initialise(user.userId());

    mvc.perform(get("/api/v1/preferences/taste-profile").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.id").value(initialised.id().toString()))
        .andExpect(jsonPath("$.documentVersion").value(1))
        .andExpect(jsonPath("$.tasteVectorStatus").value("PENDING"))
        .andExpect(jsonPath("$.document.version").value(1))
        .andExpect(jsonPath("$.document.softConstraints.intolerances.length()").value(0));
  }

  @Test
  void put_returns200_replacesProfile_writesAuditAndVersionRows_bumpsDocumentVersion()
      throws Exception {
    AuthedUser user = registerUser();
    tasteProfileUpdateService.initialise(user.userId());

    UpdateTasteProfileRequest request = TasteProfileTestData.updateRequest(0L);

    mvc.perform(
            put("/api/v1/preferences/taste-profile")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentVersion").value(2))
        .andExpect(jsonPath("$.document.version").value(2))
        .andExpect(jsonPath("$.document.flavourPreferences.likes[0]").value("umami"))
        .andExpect(jsonPath("$.tasteVectorStatus").value("PENDING"));

    // Two audit rows total: INITIALIZED + MANUAL_OVERRIDE.
    assertThat(auditLogRepository.count()).isEqualTo(2L);
    // Two version snapshots: v1 from initialise + v2 from manual override.
    assertThat(versionRepository.count()).isEqualTo(2L);
  }

  @Test
  void put_returns409_onStaleExpectedVersion() throws Exception {
    AuthedUser user = registerUser();
    tasteProfileUpdateService.initialise(user.userId());

    UpdateTasteProfileRequest stale = TasteProfileTestData.updateRequest(99L);

    mvc.perform(
            put("/api/v1/preferences/taste-profile")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stale)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

    // Stale-version short-circuits BEFORE any mutation: still only the init audit + version rows.
    assertThat(auditLogRepository.count()).isEqualTo(1L);
    assertThat(versionRepository.count()).isEqualTo(1L);
  }

  @Test
  void put_returns400_onValidationFailure_documentMissing() throws Exception {
    AuthedUser user = registerUser();
    tasteProfileUpdateService.initialise(user.userId());

    String badJson = "{\"document\":null,\"expectedVersion\":0}";

    mvc.perform(
            put("/api/v1/preferences/taste-profile")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(badJson))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void refreshNow_returns202_andCurrentState() throws Exception {
    AuthedUser user = registerUser();
    tasteProfileUpdateService.initialise(user.userId());

    mvc.perform(
            post("/api/v1/preferences/taste-profile/refresh-now")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.documentVersion").value(1));

    // Audit log gains a REFRESH_TRIGGERED row; version count unchanged.
    assertThat(auditLogRepository.count()).isEqualTo(2L);
    assertThat(versionRepository.count()).isEqualTo(1L);
  }

  @Test
  void versions_returns200_paginated_newestFirst() throws Exception {
    AuthedUser user = registerUser();
    tasteProfileUpdateService.initialise(user.userId());

    UpdateTasteProfileRequest first = TasteProfileTestData.updateRequest(0L);
    mvc.perform(
            put("/api/v1/preferences/taste-profile")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)))
        .andExpect(status().isOk());
    UpdateTasteProfileRequest second = TasteProfileTestData.updateRequest(1L);
    mvc.perform(
            put("/api/v1/preferences/taste-profile")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(second)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/preferences/taste-profile/versions").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.content[0].documentVersion").value(3))
        .andExpect(jsonPath("$.content[1].documentVersion").value(2))
        .andExpect(jsonPath("$.content[2].documentVersion").value(1));
  }

  @Test
  void versionByNumber_returns200_and404_appropriately() throws Exception {
    AuthedUser user = registerUser();
    tasteProfileUpdateService.initialise(user.userId());

    mvc.perform(get("/api/v1/preferences/taste-profile/versions/1").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentVersion").value(1));

    mvc.perform(get("/api/v1/preferences/taste-profile/versions/99").cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void auditLog_returns200_withChangesNewestFirst() throws Exception {
    AuthedUser user = registerUser();
    tasteProfileUpdateService.initialise(user.userId());

    UpdateTasteProfileRequest first = TasteProfileTestData.updateRequest(0L);
    mvc.perform(
            put("/api/v1/preferences/taste-profile")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/preferences/taste-profile/audit-log").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].changeType").value("MANUAL_OVERRIDE"))
        .andExpect(jsonPath("$.content[1].changeType").value("INITIALIZED"));
  }

  @Test
  void auditLog_returns200_emptyPage_whenProfileNotInitialised() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/preferences/taste-profile/audit-log").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void initialise_isIdempotent_andProducesEmptyDefaults() {
    UUID userId = UUID.randomUUID();
    TasteProfileDto first = tasteProfileUpdateService.initialise(userId);
    TasteProfileDto second = tasteProfileUpdateService.initialise(userId);

    assertThat(first.id()).isEqualTo(second.id());
    assertThat(first.documentVersion()).isEqualTo(1);
    assertThat(first.document().version()).isEqualTo(1);
    assertThat(first.document().basedOnFeedbackCount()).isZero();
  }
}
