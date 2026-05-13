package com.example.mealprep.discovery;

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
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
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

/** Lightweight HTTP coverage of the sources read endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class DiscoverySourcesControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private DiscoverySourceRepository sourceRepository;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM discovery_scrape_log");
    jdbcTemplate.update("DELETE FROM discovery_jobs");
    jdbcTemplate.update("DELETE FROM discovery_sources");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "src-" + AuthTestData.shortId();
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

  private DiscoverySource seedSource(String key) {
    return sourceRepository.saveAndFlush(DiscoveryTestData.sampleSource(key));
  }

  @Test
  void list_returnsSourcesSortedByDisplayName() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_b").setDisplayName("Beta");
    seedSource("src_a").setDisplayName("Alpha");

    mvc.perform(get("/api/v1/discovery/sources").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void list_anonymous_returns401() throws Exception {
    mvc.perform(get("/api/v1/discovery/sources")).andExpect(status().isUnauthorized());
  }

  @Test
  void getByKey_existing_returns200() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a");

    mvc.perform(get("/api/v1/discovery/sources/src_a").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceKey").value("src_a"));
  }

  @Test
  void getByKey_unknown_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/discovery/sources/nope").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/discovery-source-not-found"));
  }
}
