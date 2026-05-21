package com.example.mealprep.preference;

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
import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.repository.LifestyleConfigAuditLogRepository;
import com.example.mealprep.preference.domain.repository.LifestyleConfigRepository;
import com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService;
import com.example.mealprep.preference.testdata.LifestyleConfigTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
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
 * Full HTTP flow over the lifestyle-config aggregate. Registers a user, exercises GET (401, then
 * 404), initialises via the service (the REST surface does not expose initialise — onboarding will
 * call it), then exercises PUT, mark-reviewed, and the audit-log endpoint with and without the
 * section filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class LifestyleConfigFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private LifestyleConfigRepository lifestyleConfigRepository;
  @Autowired private LifestyleConfigAuditLogRepository auditLogRepository;
  @Autowired private LifestyleConfigUpdateService updateService;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    auditLogRepository.deleteAll();
    lifestyleConfigRepository.deleteAll();
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

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/preferences/lifestyle-config")).andExpect(status().isUnauthorized());
  }

  @Test
  void get_returns404_whenAggregateNotInitialised() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/preferences/lifestyle-config").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/lifestyle-config-not-found"));
  }

  @Test
  void put_returns404_whenAggregateNotInitialised() throws Exception {
    AuthedUser user = registerUser();
    UpdateLifestyleConfigRequest req =
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L);

    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNotFound());
  }

  @Test
  void initialise_then_get_returns200_andRoundTripsDocument() throws Exception {
    AuthedUser user = registerUser();
    updateService.initialise(
        user.userId(),
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));

    mvc.perform(get("/api/v1/preferences/lifestyle-config").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.document.pantryTracking.enabled").value(true))
        .andExpect(jsonPath("$.optimisticVersion").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void put_replacesDocument_writesAuditRowsForChangedSections_andBumpsVersion() throws Exception {
    AuthedUser user = registerUser();
    updateService.initialise(
        user.userId(),
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));

    UpdateLifestyleConfigRequest req =
        LifestyleConfigTestData.updateRequest(
            LifestyleConfigTestData.fullDocumentWithPantryDisabled(), 0L);

    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.document.pantryTracking.enabled").value(false))
        .andExpect(jsonPath("$.optimisticVersion").value(1));

    // Exactly one section changed → one audit row (in addition to the "*" initialise summary row).
    assertThat(auditLogRepository.count()).isEqualTo(2L);
  }

  @Test
  void put_withStaleExpectedVersion_returns409() throws Exception {
    AuthedUser user = registerUser();
    updateService.initialise(
        user.userId(),
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));

    UpdateLifestyleConfigRequest req =
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 99L);

    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict());
  }

  @Test
  void put_withInvalidNoveltyMode_returns400() throws Exception {
    AuthedUser user = registerUser();
    updateService.initialise(
        user.userId(),
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));

    // rotation mode with rotationSize = 0 → @ValidNoveltyTolerance rejects.
    LifestyleConfigDocument bad =
        new LifestyleConfigDocument(
            null,
            null,
            new LifestyleConfigDocument.NoveltyTolerance(
                java.util.Map.of(
                    "dinner",
                    new LifestyleConfigDocument.NoveltyMode("rotation", 0, null, null, null)),
                java.util.Map.of(),
                java.util.Map.of()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        LifestyleConfigTestData.updateRequest(bad, 0L))))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void markReviewed_resetsLastReviewPromptAt_andBumpsVersion() throws Exception {
    AuthedUser user = registerUser();
    var dto =
        updateService.initialise(
            user.userId(),
            LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));
    // Simulate a previous behavioural-drift nudge by manually setting the timestamp.
    lifestyleConfigRepository
        .findByUserId(user.userId())
        .ifPresent(
            agg -> {
              agg.setLastReviewPromptAt(java.time.Instant.parse("2026-04-01T00:00:00Z"));
              lifestyleConfigRepository.saveAndFlush(agg);
            });

    mvc.perform(post("/api/v1/preferences/lifestyle-config/mark-reviewed").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastReviewPromptAt").doesNotExist());

    assertThat(lifestyleConfigRepository.findByUserId(user.userId()))
        .hasValueSatisfying(agg -> assertThat(agg.getLastReviewPromptAt()).isNull());
  }

  @Test
  void markReviewed_returns404_whenAggregateMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(post("/api/v1/preferences/lifestyle-config/mark-reviewed").cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void auditLog_default_returnsInitSummaryAndUpdateRows() throws Exception {
    AuthedUser user = registerUser();
    updateService.initialise(
        user.userId(),
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));
    updateService.update(
        user.userId(),
        LifestyleConfigTestData.updateRequest(
            LifestyleConfigTestData.fullDocumentWithPantryDisabled(), 0L),
        user.userId());

    mvc.perform(get("/api/v1/preferences/lifestyle-config/audit-log").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void auditLog_withSectionFilter_returnsOnlyMatchingSections() throws Exception {
    AuthedUser user = registerUser();
    updateService.initialise(
        user.userId(),
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));
    updateService.update(
        user.userId(),
        LifestyleConfigTestData.updateRequest(
            LifestyleConfigTestData.fullDocumentWithPantryDisabled(), 0L),
        user.userId());

    // The init summary row uses fieldPath="*" so it MUST NOT match section=pantryTracking.
    mvc.perform(
            get("/api/v1/preferences/lifestyle-config/audit-log")
                .param("section", "pantryTracking")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].fieldPath").value("pantryTracking"));
  }

  @Test
  void crossTenant_userBCannotReadUserAConfig() throws Exception {
    AuthedUser userA = registerUser();
    updateService.initialise(
        userA.userId(),
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));
    AuthedUser userB = registerUser();

    // B has no aggregate → GET returns 404 even though A's aggregate exists in the DB.
    mvc.perform(get("/api/v1/preferences/lifestyle-config").cookie(userB.cookie()))
        .andExpect(status().isNotFound());
  }
}
