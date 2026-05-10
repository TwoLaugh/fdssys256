package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.example.mealprep.provisions.event.EquipmentChangedEvent;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
 * Full HTTP flow over the equipment aggregate. Exercises GET / PUT (insert + update) / DELETE,
 * version-conflict 409s, validation 400s, and the {@link EquipmentChangedEvent} AFTER_COMMIT
 * publication.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  EquipmentFlowIT.EquipmentEventCaptureConfig.class
})
@ActiveProfiles("test")
class EquipmentFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private EquipmentEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_equipment");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
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

  // ---------------- PUT (create) ----------------

  @Test
  void put_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            put("/api/v1/provisions/equipment/oven")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":true}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void put_returns201_onFirstCall_andPublishesEvent() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(
            put("/api/v1/provisions/equipment/oven")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":true,\"details\":\"Stainless steel\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.name").value("oven"))
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).equipmentName()).isEqualTo("oven");
    assertThat(eventCapture.events().get(0).nowAvailable()).isTrue();
  }

  @Test
  void put_returns200_onSecondCall_andBumpsVersion() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/equipment/oven")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":true}"))
        .andExpect(status().isCreated());

    mvc.perform(
            put("/api/v1/provisions/equipment/oven")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":false,\"expectedVersion\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void put_returns409_whenStaleExpectedVersion() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/equipment/oven")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":true}"))
        .andExpect(status().isCreated());

    mvc.perform(
            put("/api/v1/provisions/equipment/oven")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":false,\"expectedVersion\":99}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void put_returns409_whenExpectedVersionMissing_onUpdate() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/equipment/oven")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":true}"))
        .andExpect(status().isCreated());

    mvc.perform(
            put("/api/v1/provisions/equipment/oven")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":false}"))
        .andExpect(status().isConflict());
  }

  @Test
  void put_returns400_whenNameDoesNotMatchPattern() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/equipment/Bad-Name")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":true}"))
        .andExpect(status().isBadRequest());
  }

  // ---------------- GET ----------------

  @Test
  void get_returnsCallerEquipmentSortedByName() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
        put("/api/v1/provisions/equipment/oven")
            .cookie(user.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"available\":true}"));
    mvc.perform(
        put("/api/v1/provisions/equipment/air_fryer")
            .cookie(user.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"available\":true}"));

    mvc.perform(get("/api/v1/provisions/equipment").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("air_fryer"))
        .andExpect(jsonPath("$[1].name").value("oven"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void get_doesNotLeakOtherUsersEquipment() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser other = registerUser();
    mvc.perform(
        put("/api/v1/provisions/equipment/oven")
            .cookie(owner.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"available\":true}"));

    mvc.perform(get("/api/v1/provisions/equipment").cookie(other.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/provisions/equipment")).andExpect(status().isUnauthorized());
  }

  // ---------------- DELETE ----------------

  @Test
  void delete_returns204_andPublishesEventWithNowAvailableFalse() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
        put("/api/v1/provisions/equipment/oven")
            .cookie(user.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"available\":true}"));
    eventCapture.clear();

    mvc.perform(delete("/api/v1/provisions/equipment/oven").cookie(user.cookie()))
        .andExpect(status().isNoContent());

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).nowAvailable()).isFalse();
  }

  @Test
  void delete_returns404_whenMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(delete("/api/v1/provisions/equipment/ghost").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/equipment-not-found"));
  }

  @Test
  void delete_returns401_whenAnonymous() throws Exception {
    mvc.perform(delete("/api/v1/provisions/equipment/oven")).andExpect(status().isUnauthorized());
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class EquipmentEventCaptureConfig {
    @Bean
    EquipmentEventCapture equipmentEventCapture() {
      return new EquipmentEventCapture();
    }
  }

  static class EquipmentEventCapture {
    private final List<EquipmentChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onChanged(EquipmentChangedEvent event) {
      events.add(event);
    }

    public List<EquipmentChangedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
