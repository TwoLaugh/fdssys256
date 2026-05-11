package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.LifestyleConfigDocument;
import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.household.api.dto.TasteProfileDocument;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.spi.SoftPreferencesReader;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Sibling to {@code HouseholdMergeFlowIT} that swaps the {@link SoftPreferencesReader} for a fake —
 * proves the SPI lookup picks up the test-provided implementation (so preference-01c will be able
 * to wire its own reader the same way).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  HouseholdMergeWithFakeReaderIT.FakeReaderConfig.class
})
@ActiveProfiles("test")
class HouseholdMergeWithFakeReaderIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HouseholdUpdateService householdUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private SoftPreferencesReader softPreferencesReader;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM household_invite");
    jdbcTemplate.update("DELETE FROM household_settings_audit");
    jdbcTemplate.update("DELETE FROM household_settings");
    jdbcTemplate.update("DELETE FROM household_member");
    jdbcTemplate.update("DELETE FROM household");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void merge_fakeReader_winsOverNoop_andOutputReflectsBundle() throws Exception {
    assertThat(softPreferencesReader.getClass().getSimpleName())
        .isEqualTo("FakeSoftPreferencesReader");

    String username = "fake-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    UUID userId =
        UUID.fromString(
            objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("userId")
                .asText());
    householdUpdateService.createHousehold(userId, new CreateHouseholdRequest("X"));

    mvc.perform(
            post("/api/v1/households/current/merge")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mergedTasteProfile.ingredientLikes.onion").value(0.7));
  }

  /**
   * Provides a {@link SoftPreferencesReader} bean — the Noop is conditional on missing bean and
   * therefore steps aside, so this one wins.
   */
  @TestConfiguration
  static class FakeReaderConfig {
    /**
     * @Primary so Spring picks this over the Noop when both are registered. The Noop's
     * {@code @ConditionalOnMissingBean} doesn't reliably defer to {@code @TestConfiguration} beans
     * because the conditional evaluates before the test config is applied.
     */
    @Bean
    @Primary
    SoftPreferencesReader softPreferencesReader() {
      return new FakeSoftPreferencesReader();
    }
  }

  /** Class-name pinned so the assertion above can verify the right bean is wired. */
  static class FakeSoftPreferencesReader implements SoftPreferencesReader {
    @Override
    public List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds) {
      return userIds.stream()
          .map(
              u ->
                  new SoftPreferenceBundleDto(
                      u,
                      new TasteProfileDocument(
                          Map.of("onion", new BigDecimal("0.7")), Map.of(), List.of()),
                      new LifestyleConfigDocument(null, null, null, false)))
          .toList();
    }
  }
}
