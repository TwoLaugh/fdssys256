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
import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.repository.LifestyleConfigAuditLogRepository;
import com.example.mealprep.preference.domain.repository.LifestyleConfigRepository;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import com.example.mealprep.preference.testdata.LifestyleConfigTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Upsert-on-first-PUT (onboarding G1) over both onboarding write surfaces: a brand-new user — no
 * hard-constraints row, no lifestyle-config row — completes wizard steps 3 and 4 over plain REST
 * with {@code expectedVersion = 0}, with no internal initialise call.
 *
 * <p>Also locks the contract's edges: {@code expectedVersion > 0} on an absent aggregate stays 404
 * (stale client, not create intent); {@code expectedVersion = 0} on an already-moved-on aggregate
 * stays 409; the GAP-04 Tier-1-removal interstitial never fires on the create path (first write is
 * additive) but stays armed for the next PUT; a concurrent create double-submit never yields two
 * rows or a 5xx (the {@code user_id} unique race loser maps to the optimistic-lock 409).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class PreferenceOnboardingFirstWriteIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HardConstraintsRepository hardConstraintsRepository;
  @Autowired private HardConstraintsAuditLogRepository hardConstraintsAuditLogRepository;
  @Autowired private LifestyleConfigRepository lifestyleConfigRepository;
  @Autowired private LifestyleConfigAuditLogRepository lifestyleConfigAuditLogRepository;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    hardConstraintsAuditLogRepository.deleteAll();
    hardConstraintsRepository.deleteAll();
    lifestyleConfigAuditLogRepository.deleteAll();
    lifestyleConfigRepository.deleteAll();
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

  /** Onboarding step-3 shaped payload: allergy chips + identity, expectedVersion 0. */
  private UpdateHardConstraintsRequest step3Request() {
    return HardConstraintsTestData.updateRequest()
        .withAllergies("peanuts")
        .withDietaryIdentity(new DietaryIdentityDto("vegetarian", null, List.of()))
        .withExpectedVersion(0L)
        .build();
  }

  // ---------------- the onboarding flow (ticket DoD) ----------------

  @Test
  void onboardingSteps3And4_freshUser_firstWritesSucceed_andGetsReturnTheDocuments()
      throws Exception {
    AuthedUser user = registerUser();

    // Wizard resume probes (§4): both GETs 404 before any write — absent-until-touched survives.
    mvc.perform(get("/api/v1/preferences/hard-constraints").cookie(user.cookie()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/preferences/lifestyle-config").cookie(user.cookie()))
        .andExpect(status().isNotFound());

    // Step 3: first PUT (expectedVersion 0, no row) creates + applies in one shot → 200, version 1
    // (create at 0, apply bumps to 1).
    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(step3Request())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allergies[0]").value("peanuts"))
        .andExpect(jsonPath("$.dietaryIdentity.base").value("vegetarian"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(openApi().isValid(openApiValidator));

    // Step 4: first PUT creates the lifestyle config with the inbound document → 200, version 0.
    UpdateLifestyleConfigRequest step4 =
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L);
    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(step4)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.document.pantryTracking.enabled").value(true))
        .andExpect(jsonPath("$.optimisticVersion").value(0))
        .andExpect(openApi().isValid(openApiValidator));

    // Both GETs now return the created documents.
    mvc.perform(get("/api/v1/preferences/hard-constraints").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.allergies[0]").value("peanuts"));
    mvc.perform(get("/api/v1/preferences/lifestyle-config").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.document.pantryTracking.enabled").value(true));

    // Audit trail of the create: per-field rows for the hard constraints (actor = the user, via
    // the ordinary apply machinery), the single "*" summary row for the lifestyle config.
    assertThat(hardConstraintsAuditLogRepository.count()).isEqualTo(2L); // allergies + base
    assertThat(lifestyleConfigAuditLogRepository.count()).isEqualTo(1L);
    mvc.perform(get("/api/v1/preferences/hard-constraints/audit-log").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].actorUserId").value(user.userId().toString()));
  }

  // ---------------- GAP-04 stays intact ----------------

  @Test
  void firstWrite_isAdditive_neverTriggersGap04_butGateStaysArmedForTheNextPut() throws Exception {
    AuthedUser user = registerUser();

    // Create-path PUT changes base omnivore-default→vegetarian AND adds an allergy: no prior
    // constraints exist, so the Tier-1 interstitial must NOT fire (200, not 409).
    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(step3Request())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));

    // Regression: the very next PUT that drops the allergy without confirmation hits the
    // unweakened GAP-04 interstitial.
    UpdateHardConstraintsRequest removal =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(new DietaryIdentityDto("vegetarian", null, List.of()))
            .withExpectedVersion(1L)
            .build();
    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(removal)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.reason").value("TIER1_REMOVAL_REQUIRES_CONFIRMATION"))
        .andExpect(jsonPath("$.removedConstraints[0].value").value("peanuts"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- contract edges ----------------

  @Test
  void put_hardConstraints_absentAggregate_withStaleExpectedVersion_returns404_andCreatesNothing()
      throws Exception {
    AuthedUser user = registerUser();

    UpdateHardConstraintsRequest stale =
        HardConstraintsTestData.updateRequest()
            .withAllergies("peanuts")
            .withExpectedVersion(3L)
            .build();
    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stale)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(hardConstraintsRepository.findByUserId(user.userId())).isEmpty();
  }

  @Test
  void put_hardConstraints_expectedVersionZero_onExistingAggregate_returns409() throws Exception {
    AuthedUser user = registerUser();

    // First write lands the aggregate at version 1...
    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(step3Request())))
        .andExpect(status().isOk());

    // ...so a replayed create-shaped PUT (expectedVersion 0) is stale → 409, no clobber.
    mvc.perform(
            put("/api/v1/preferences/hard-constraints")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        HardConstraintsTestData.updateRequest()
                            .withAllergies("shellfish")
                            .withExpectedVersion(0L)
                            .build())))
        .andExpect(status().isConflict());

    // The stored state is untouched by the stale write.
    mvc.perform(get("/api/v1/preferences/hard-constraints").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allergies[0]").value("peanuts"));
  }

  @Test
  void put_lifestyleConfig_absentAggregate_withStaleExpectedVersion_returns404_andCreatesNothing()
      throws Exception {
    AuthedUser user = registerUser();

    UpdateLifestyleConfigRequest stale =
        LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 3L);
    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stale)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(lifestyleConfigRepository.findByUserId(user.userId())).isEmpty();
  }

  @Test
  void put_lifestyleConfig_expectedVersionZero_afterAggregateMovedOn_returns409() throws Exception {
    AuthedUser user = registerUser();

    // First write creates at version 0; a genuine update moves it to version 1.
    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        LifestyleConfigTestData.updateRequest(
                            LifestyleConfigTestData.fullDocument(), 0L))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.optimisticVersion").value(0));
    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        LifestyleConfigTestData.updateRequest(
                            LifestyleConfigTestData.fullDocumentWithPantryDisabled(), 0L))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.optimisticVersion").value(1));

    // A replayed create-shaped PUT (expectedVersion 0) is now stale → 409.
    mvc.perform(
            put("/api/v1/preferences/lifestyle-config")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        LifestyleConfigTestData.updateRequest(
                            LifestyleConfigTestData.fullDocument(), 0L))))
        .andExpect(status().isConflict());
  }

  // ---------------- concurrent create double-submit ----------------

  /**
   * Two simultaneous first-write PUTs for the same user. Whatever the interleaving, the {@code
   * user_id} unique constraint guarantees exactly one aggregate row; the loser surfaces as the
   * optimistic-lock 409 (unique-race loser via the create path's translation, or a plain stale
   * version when the requests serialised) — never a 5xx, never a duplicate.
   */
  @Test
  void concurrent_firstWrite_hardConstraints_oneWins_loserGets409_andOneRowExists()
      throws Exception {
    AuthedUser user = registerUser();
    List<Integer> statuses =
        racingPuts(
            "/api/v1/preferences/hard-constraints",
            user,
            2,
            objectMapper.writeValueAsString(step3Request()));

    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    assertThat(hardConstraintsRepository.count()).isEqualTo(1L);
  }

  /**
   * Lifestyle-config double-submit: the create leaves the row at version 0, so a fully-serialised
   * identical re-submit is a legitimate no-op 200 rather than a 409 — both interleavings are
   * asserted safe: no 5xx, at least one 200, and exactly one row.
   */
  @Test
  void concurrent_firstWrite_lifestyleConfig_neverDuplicates_andNeverErrors() throws Exception {
    AuthedUser user = registerUser();
    List<Integer> statuses =
        racingPuts(
            "/api/v1/preferences/lifestyle-config",
            user,
            2,
            objectMapper.writeValueAsString(
                LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L)));

    assertThat(statuses).contains(200);
    assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(200, 409));
    assertThat(lifestyleConfigRepository.count()).isEqualTo(1L);
  }

  /** Fire {@code threads} identical PUTs at {@code path} simultaneously; return their statuses. */
  private List<Integer> racingPuts(String path, AuthedUser user, int threads, String body)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return mvc.perform(
                          put(path)
                              .cookie(user.cookie())
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : futures) {
        statuses.add(f.get(30, TimeUnit.SECONDS));
      }
      return statuses;
    } finally {
      pool.shutdownNow();
    }
  }
}
