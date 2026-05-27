package com.example.mealprep.grocery;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.grocery.api.dto.RecordManualPriceRequest;
import com.example.mealprep.grocery.api.dto.RefreshPricesRequest;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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
 * HTTP flow over the Tier-4 price-history endpoints (01c). Seeds via the manual-record endpoint (so
 * the write + read paths are exercised together) and reads back aggregates/observations. Repository
 * persistence is real (Testcontainers Postgres); the reference fallback uses the bundled {@code
 * R__grocery_seed_reference_prices} snapshot ("chicken breast" = 110 p/100g).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class PriceHistoryControllerIT {

  private static final String BASE = "/api/v1/grocery/price-history";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM grocery_price_history");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("groc-" + AuthTestData.shortId());
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userId), cookie);
  }

  private void recordManual(
      Cookie cookie, String key, String store, int totalPence, String quantity) throws Exception {
    RecordManualPriceRequest body =
        new RecordManualPriceRequest(
            key, store, totalPence, new BigDecimal(quantity), "per_100g", null);
    mvc.perform(
            post(BASE + "/observations/manual")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());
  }

  // ---------------- auth + validation ----------------

  @Test
  void aggregate_anonymous_returns401() throws Exception {
    mvc.perform(get(BASE + "/aggregates").param("ingredientKey", "chicken breast"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void recordManual_blankStore_returns400() throws Exception {
    AuthedUser user = registerUser();
    RecordManualPriceRequest body =
        new RecordManualPriceRequest("chicken breast", "  ", 100, BigDecimal.ONE, "per_100g", null);
    mvc.perform(
            post(BASE + "/observations/manual")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  // ---------------- manual record + aggregate ----------------

  @Test
  void recordManual_then_aggregate_returnsEstimateFromObservations() throws Exception {
    AuthedUser user = registerUser();
    recordManual(user.cookie(), "chicken breast", "tesco_online", 200, "1");
    recordManual(user.cookie(), "chicken breast", "tesco_online", 220, "1");

    mvc.perform(
            get(BASE + "/aggregates")
                .param("ingredientKey", "chicken breast")
                .param("store", "tesco_online")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ingredientMappingKey").value("chicken breast"))
        .andExpect(jsonPath("$.sampleCount").value(2))
        .andExpect(jsonPath("$.pointEstimatePence").value(210))
        .andExpect(jsonPath("$.isStale").value(false))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void aggregate_noObservations_referenceSeedKey_returnsReferenceEstimate() throws Exception {
    AuthedUser user = registerUser();
    // No observations recorded; "chicken breast" is in the bundled reference snapshot (110 p/100g).
    mvc.perform(
            get(BASE + "/aggregates")
                .param("ingredientKey", "chicken breast")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sampleCount").value(0))
        .andExpect(jsonPath("$.pointEstimatePence").value(110));
  }

  @Test
  void aggregate_unknownKey_notInReference_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get(BASE + "/aggregates")
                .param("ingredientKey", "definitely-not-a-real-ingredient-xyz")
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- listing endpoints ----------------

  @Test
  void crossStore_returnsPerStoreAggregates() throws Exception {
    AuthedUser user = registerUser();
    recordManual(user.cookie(), "white rice", "tesco_online", 30, "1");
    recordManual(user.cookie(), "white rice", "aldi", 20, "1");

    mvc.perform(
            get(BASE + "/aggregates/cross-store")
                .param("ingredientKey", "white rice")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void observations_paged_returnsRecordedRows() throws Exception {
    AuthedUser user = registerUser();
    recordManual(user.cookie(), "broccoli", "tesco_online", 40, "1");

    mvc.perform(get(BASE + "/observations").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].ingredientMappingKey").value("broccoli"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void observationsByKey_paged_filtersToKey() throws Exception {
    AuthedUser user = registerUser();
    recordManual(user.cookie(), "broccoli", "tesco_online", 40, "1");
    recordManual(user.cookie(), "olive oil", "tesco_online", 70, "1");

    mvc.perform(
            get(BASE + "/observations/by-key")
                .param("ingredientKey", "broccoli")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].ingredientMappingKey").value("broccoli"));
  }

  // ---------------- refresh ----------------

  @Test
  void refresh_withoutProviderQuote_returns200_noObservationsWritten() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post(BASE + "/refresh")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshPricesRequest(false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.observationsWritten").value(0))
        .andExpect(jsonPath("$.aiUnavailableFallbackUsed").value(false));
  }
}
