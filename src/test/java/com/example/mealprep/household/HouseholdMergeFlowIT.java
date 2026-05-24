package com.example.mealprep.household;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.spi.SoftPreferencesReader;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full HTTP flow over {@code POST /api/v1/households/current/merge}. With the preference module on
 * the classpath the real {@code PreferenceSoftPreferencesReader} {@code @Component} wins over the
 * household {@code NoopSoftPreferencesReader} (household/preference-01g) — so a member with NO
 * persisted soft prefs still yields a null-fielded bundle and the merge degrades to empty docs, and
 * a member WITH a persisted taste profile drives a non-empty {@code mergedTasteProfile}. The
 * fake-reader-wins variant lives in a sibling {@code HouseholdMergeWithFakeReaderIT} to avoid the
 * nested-class surefire/failsafe ambiguity (Surefire would otherwise pick up nested
 * {@code @SpringBootTest} classes via their {@code .class} file name, which doesn't match the
 * {@code *IT.class} exclude).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class HouseholdMergeFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HouseholdUpdateService householdUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private SoftPreferencesReader softPreferencesReader;
  @Autowired private TasteProfileUpdateService tasteProfileUpdateService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM household_invite");
    jdbcTemplate.update("DELETE FROM household_settings_audit");
    jdbcTemplate.update("DELETE FROM household_settings");
    jdbcTemplate.update("DELETE FROM household_member");
    jdbcTemplate.update("DELETE FROM household");
    jdbcTemplate.update("DELETE FROM preference_taste_profile_audit");
    jdbcTemplate.update("DELETE FROM preference_taste_profile_versions");
    jdbcTemplate.update("DELETE FROM preference_taste_profile");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
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

  // ---------------- 200 happy paths ----------------

  @Test
  void merge_byPrimary_nullEaters_memberWithNoSoftPrefs_returns200_withEmptyDocs()
      throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("Smith"));

    // Real reader is wired, but the member has no persisted taste profile / lifestyle config →
    // null-fielded bundle → the merger degrades to empty docs (same observable result the Noop
    // gave).
    mvc.perform(
            post("/api/v1/households/current/merge")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contributingUserIds.length()").value(1))
        .andExpect(jsonPath("$.contributingUserIds[0]").value(primary.userId().toString()))
        .andExpect(jsonPath("$.strategy").value("MEAN_WEIGHTED_BY_PRIORITY"))
        .andExpect(jsonPath("$.mergedTasteProfile.avoidList").isEmpty())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void merge_byPrimary_memberWithTasteProfile_returns200_withNonEmptyMergedTasteProfile()
      throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("Smith"));
    // Seed a real preference taste profile (favourite salmon, disliked kale) so the REAL reader
    // returns a populated bundle and the merge is non-empty (was empty under the Noop).
    tasteProfileUpdateService.initialise(primary.userId());
    tasteProfileUpdateService.applyManualOverride(
        primary.userId(),
        TasteProfileTestData.updateRequestWithDocument(
            TasteProfileTestData.populatedDocument(0), 0L),
        primary.userId());

    mvc.perform(
            post("/api/v1/households/current/merge")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contributingUserIds.length()").value(1))
        // A favourite projects to +1 and a disliked to -1 (single primary member → the
        // weighted-mean merge passes the value through unchanged). The merged BigDecimal
        // like-scores
        // are whole numbers (scale 0), so Jackson serialises them as JSON integers — assert the
        // integer literals (Hamcrest closeTo only matches Double, not the Integer JsonPath yields).
        .andExpect(jsonPath("$.mergedTasteProfile.ingredientLikes.salmon").value(1))
        .andExpect(jsonPath("$.mergedTasteProfile.ingredientLikes.kale").value(-1))
        .andExpect(jsonPath("$.mergedTasteProfile.avoidList[0]").value("kale"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void merge_byPrimary_emptyEaters_treatedAsNull() throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));

    mvc.perform(
            post("/api/v1/households/current/merge")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eaterUserIds\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contributingUserIds.length()").value(1));
  }

  @Test
  void merge_realPreferenceReaderWins_overNoop() {
    // With the preference module on the classpath, the real @Component
    // PreferenceSoftPreferencesReader
    // out-ranks the household Noop @Bean @ConditionalOnMissingBean — so the autowired SPI bean is
    // the
    // real reader (household/preference-01g).
    assertThat(softPreferencesReader.getClass().getName())
        .isEqualTo("com.example.mealprep.preference.spi.internal.PreferenceSoftPreferencesReader");
  }

  // ---------------- 4xx ----------------

  @Test
  void merge_anonymous_returns401() throws Exception {
    mvc.perform(
            post("/api/v1/households/current/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void merge_byOrphan_returns404() throws Exception {
    AuthedUser orphan = registerUser("orphan");
    mvc.perform(
            post("/api/v1/households/current/merge")
                .cookie(orphan.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/household-not-found"));
  }

  @Test
  void merge_eaterOutsideHousehold_returns403() throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    UUID outsider = UUID.randomUUID();
    String body = "{\"eaterUserIds\":[\"" + outsider + "\"]}";
    mvc.perform(
            post("/api/v1/households/current/merge")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/insufficient-household-role"));
  }

  @Test
  void merge_nullElementInEaterList_returns400() throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));
    mvc.perform(
            post("/api/v1/households/current/merge")
                .cookie(primary.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eaterUserIds\":[null]}"))
        .andExpect(status().isBadRequest());
  }
}
