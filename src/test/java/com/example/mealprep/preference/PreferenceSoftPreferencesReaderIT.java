package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.household.spi.SoftPreferencesReader;
import com.example.mealprep.preference.domain.repository.LifestyleConfigRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.spi.internal.PreferenceSoftPreferencesReader;
import com.example.mealprep.preference.testdata.LifestyleConfigTestData;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * Testcontainers IT for the preference-side soft-preference read (household/preference-01g): real
 * persisted taste profiles + lifestyle configs are projected to bundles by {@link
 * PreferenceQueryService#getSoftPreferencesByUserIds(List)}, exercising the JSONB read path against
 * real Postgres. Asserts the SPI count/order invariant, that the real {@link
 * PreferenceSoftPreferencesReader} wins over the household Noop on the classpath, and that batching
 * a multi-user household issues a bounded number of queries (no N+1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PreferenceSoftPreferencesReaderIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private TasteProfileRepository tasteProfileRepository;
  @Autowired private LifestyleConfigRepository lifestyleConfigRepository;
  @Autowired private TasteProfileUpdateService tasteProfileUpdateService;
  @Autowired private LifestyleConfigUpdateService lifestyleConfigUpdateService;
  @Autowired private PreferenceQueryService preferenceQueryService;
  @Autowired private SoftPreferencesReader softPreferencesReader;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @AfterEach
  void cleanup() {
    tasteProfileRepository.deleteAll();
    lifestyleConfigRepository.deleteAll();
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  // ---------------- helpers ----------------

  private UUID registerUser(String prefix) throws Exception {
    String username = prefix + "-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText());
  }

  private void seedTasteProfile(UUID userId) {
    tasteProfileUpdateService.initialise(userId);
    tasteProfileUpdateService.applyManualOverride(
        userId,
        TasteProfileTestData.updateRequestWithDocument(
            TasteProfileTestData.populatedDocument(0), 0L),
        userId);
  }

  private void seedLifestyleConfig(UUID userId) {
    lifestyleConfigUpdateService.initialise(
        userId, LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));
  }

  private Statistics hibernateStats() {
    Statistics s = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    s.setStatisticsEnabled(true);
    s.clear();
    return s;
  }

  // ---------------- the real reader wins on the classpath ----------------

  @Test
  void realPreferenceReaderWinsOverNoop_whenPreferenceOnClasspath() {
    assertThat(softPreferencesReader).isInstanceOf(PreferenceSoftPreferencesReader.class);
  }

  // ---------------- real bundles per user ----------------

  @Test
  void getSoftPreferencesByUserIds_realPersistedData_projectsNonVectorBundle() throws Exception {
    UUID userId = registerUser("solo");
    seedTasteProfile(userId);
    seedLifestyleConfig(userId);

    List<SoftPreferenceBundleDto> bundles =
        preferenceQueryService.getSoftPreferencesByUserIds(List.of(userId));

    assertThat(bundles).hasSize(1);
    SoftPreferenceBundleDto bundle = bundles.get(0);
    assertThat(bundle.userId()).isEqualTo(userId);
    // populatedDocument: favourite salmon (+1), disliked kale (-1) → kale also avoided.
    assertThat(bundle.tasteProfile().ingredientLikes())
        .containsEntry("salmon", BigDecimal.ONE)
        .containsEntry("kale", BigDecimal.ONE.negate());
    assertThat(bundle.tasteProfile().avoidList()).contains("kale");
    // fullDocument lifestyle: breakfast 07:00-08:00 + dinner 19:00-20:00 → window 07:00..20:00.
    assertThat(bundle.lifestyleConfig().mealTimingWindowStart()).isEqualTo("07:00");
    assertThat(bundle.lifestyleConfig().mealTimingWindowEnd()).isEqualTo("20:00");
    assertThat(bundle.lifestyleConfig().batchCookingPreferred()).isTrue();
  }

  // ---------------- count + order invariant, null-fielded for missing ----------------

  @Test
  void getSoftPreferencesByUserIds_preservesOrderAndCount_withNullFieldedForMissing()
      throws Exception {
    UUID first = registerUser("first");
    UUID second = registerUser("second");
    UUID third = registerUser("third");
    seedTasteProfile(first);
    seedLifestyleConfig(third);
    // `second` has no soft prefs at all.

    List<UUID> input = List.of(first, second, third);
    List<SoftPreferenceBundleDto> bundles =
        preferenceQueryService.getSoftPreferencesByUserIds(input);

    assertThat(bundles).hasSize(3);
    assertThat(bundles.stream().map(SoftPreferenceBundleDto::userId).toList())
        .containsExactly(first, second, third);
    assertThat(bundles.get(0).tasteProfile()).isNotNull();
    assertThat(bundles.get(0).lifestyleConfig()).isNull();
    // The user with no soft prefs is present (not omitted) with a fully null-fielded bundle.
    assertThat(bundles.get(1).tasteProfile()).isNull();
    assertThat(bundles.get(1).lifestyleConfig()).isNull();
    assertThat(bundles.get(2).tasteProfile()).isNull();
    assertThat(bundles.get(2).lifestyleConfig()).isNotNull();
  }

  // ---------------- no N+1 ----------------

  @Test
  void getSoftPreferencesByUserIds_fourEaterHousehold_boundedQueryCount() throws Exception {
    UUID a = registerUser("a");
    UUID b = registerUser("b");
    UUID c = registerUser("c");
    UUID d = registerUser("d");
    for (UUID u : List.of(a, b, c, d)) {
      seedTasteProfile(u);
      seedLifestyleConfig(u);
    }

    Statistics stats = hibernateStats();
    List<SoftPreferenceBundleDto> bundles =
        preferenceQueryService.getSoftPreferencesByUserIds(List.of(a, b, c, d));

    assertThat(bundles).hasSize(4);
    // Two batch-loads (taste profiles + lifestyle configs) regardless of eater count — no N+1.
    long executed = stats.getQueryExecutionCount() + stats.getPrepareStatementCount();
    assertThat(executed)
        .as("4-eater soft-pref read should not trigger N+1 — got %d statements", executed)
        .isLessThanOrEqualTo(4);
  }
}
