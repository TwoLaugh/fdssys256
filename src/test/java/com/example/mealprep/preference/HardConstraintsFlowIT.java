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
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
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
 * Full HTTP flow over the hard-constraints aggregate. Registers a user, exercises GET (404 then 200
 * after seeding), PUT (200 — full replacement, then 409 on stale version, then 400 on bad payload),
 * and audit-log pagination.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class HardConstraintsFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HardConstraintsRepository hardConstraintsRepository;
  @Autowired private HardConstraintsAuditLogRepository auditLogRepository;
  @Autowired private PreferenceUpdateService preferenceUpdateService;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    auditLogRepository.deleteAll();
    hardConstraintsRepository.deleteAll();
    sessionRepository.deleteAll();
    userRepository.deleteAll();
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

  // ---------------- tests ----------------

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/preferences/hard-constraints")).andExpect(status().isUnauthorized());
  }

  @Test
  void get_returns404_whenAggregateNotInitialised() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/preferences/hard-constraints").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/hard-constraints-not-found"));
  }

  @Test
  void get_returns200_withDefaultsAfterInitialise() throws Exception {
    AuthedUser user = registerUser();
    HardConstraintsDto initialised =
        preferenceUpdateService.initialiseHardConstraints(user.userId());

    mvc.perform(get("/api/v1/preferences/hard-constraints").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.id").value(initialised.id().toString()))
        .andExpect(jsonPath("$.dietaryIdentity.base").value("omnivore"))
        .andExpect(jsonPath("$.allergies").isArray())
        .andExpect(jsonPath("$.allergies.length()").value(0))
        .andExpect(jsonPath("$.medicalDiets.length()").value(0))
        .andExpect(jsonPath("$.intolerances.length()").value(0))
        .andExpect(jsonPath("$.ageRestrictions.length()").value(0))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void put_returns200_replacesAggregate_andWritesAuditRowsForChangedFields() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withAllergies("peanuts", "shellfish")
            .withDietaryIdentity(HardConstraintsTestData.vegetarianIdentityWithFishOnWeekends())
            .withMedicalDiets("low_sodium")
            .withIntolerances(HardConstraintsTestData.lactoseIntolerance())
            .withAgeRestrictions(HardConstraintsTestData.noWholeNutsRestriction())
            .withExpectedVersion(0L)
            .build();

    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allergies.length()").value(2))
        .andExpect(jsonPath("$.allergies[0]").value("peanuts"))
        .andExpect(jsonPath("$.allergies[1]").value("shellfish"))
        .andExpect(jsonPath("$.dietaryIdentity.base").value("vegetarian"))
        .andExpect(jsonPath("$.dietaryIdentity.exceptions.length()").value(1))
        .andExpect(jsonPath("$.dietaryIdentity.exceptions[0].allows").value("fish"))
        .andExpect(jsonPath("$.medicalDiets[0]").value("low_sodium"))
        .andExpect(jsonPath("$.intolerances[0].substance").value("lactose"))
        .andExpect(jsonPath("$.ageRestrictions[0].ruleKey").value("no_whole_nuts"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(openApi().isValid(openApiValidator));

    // Audit rows landed: one per genuinely changed field (7 total here — initial state is all
    // empty/default, every field changed). The HTTP response above already validates that the
    // children persisted (exceptions/intolerances/ageRestrictions all show in jsonPath asserts);
    // a DB-side re-fetch would need its own transaction to safely traverse the lazy collections.
    long auditRowCount = auditLogRepository.count();
    assertThat(auditRowCount).isEqualTo(7L);
  }

  @Test
  void put_returns409_onStaleExpectedVersion() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withAllergies("peanuts")
            .withExpectedVersion(99L)
            .build();

    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

    // No audit rows written because the version mismatch shorts the path before mutation.
    assertThat(auditLogRepository.count()).isZero();
  }

  @Test
  void put_returns400_onValidationFailure() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    // dietaryIdentity is null → @NotNull fires.
    String badJson =
        "{\"allergies\":[],\"dietaryIdentity\":null,\"medicalDiets\":[],"
            + "\"intolerances\":[],\"ageRestrictions\":[],\"expectedVersion\":0}";

    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(badJson))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[?(@.field == 'dietaryIdentity')]").exists());
  }

  @Test
  void put_whenNoFieldsChanged_returns200_butWritesNoAuditRow_andDoesNotBumpVersion()
      throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    // Match defaults exactly — no fields differ.
    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(HardConstraintsTestData.omnivoreIdentity())
            .withExpectedVersion(0L)
            .build();

    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0));

    assertThat(auditLogRepository.count()).isZero();
  }

  @Test
  void auditLog_returns200_withChangesNewestFirst() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    // Change one field at a time so the response order is testable.
    UpdateHardConstraintsRequest first =
        HardConstraintsTestData.updateRequest()
            .withAllergies("peanuts")
            .withExpectedVersion(0L)
            .build();
    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)))
        .andExpect(status().isOk());

    UpdateHardConstraintsRequest second =
        HardConstraintsTestData.updateRequest()
            .withAllergies("peanuts")
            .withMedicalDiets("low_sodium")
            .withExpectedVersion(1L)
            .build();
    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(second)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/preferences/hard-constraints/audit-log").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        // Newest-first: medicalDiets change happened second.
        .andExpect(jsonPath("$.content[0].fieldChanged").value("medicalDiets"))
        .andExpect(jsonPath("$.content[1].fieldChanged").value("allergies"))
        .andExpect(jsonPath("$.content[0].actorUserId").value(user.userId().toString()));
  }

  @Test
  void auditLog_returns200_emptyPage_whenAggregateNotInitialised() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/preferences/hard-constraints/audit-log").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void initialiseHardConstraints_isIdempotent_andProducesOmnivoreDefaults() {
    UUID userId = UUID.randomUUID();
    HardConstraintsDto first = preferenceUpdateService.initialiseHardConstraints(userId);
    HardConstraintsDto second = preferenceUpdateService.initialiseHardConstraints(userId);

    assertThat(first.id()).isEqualTo(second.id());
    assertThat(first.dietaryIdentity().base()).isEqualTo("omnivore");
    assertThat(first.allergies()).isEmpty();
    assertThat(first.intolerances()).isEmpty();
  }
}
