package com.example.mealprep.notification;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.mealprep.notification.api.dto.CreateNotificationRequest;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
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

/** Full HTTP-flow IT over {@code NotificationsController}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class NotificationsControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private NotificationUpdateService updateService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM notification_delivery_log");
    jdbcTemplate.update("DELETE FROM notifications");
    jdbcTemplate.update("DELETE FROM notification_preferences");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("notif-" + AuthTestData.shortId());
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
    return new AuthedUser(userId, cookie);
  }

  private UUID seedNotification(UUID userId) {
    var payload =
        new NotificationPayload.ItemNearExpiryPayload(
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            List.of(UUID.randomUUID()),
            LocalDate.now(),
            1);
    return updateService
        .create(
            new CreateNotificationRequest(
                userId,
                null,
                NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
                NotificationSeverity.ATTENTION,
                "Items nearing expiry",
                "1 item nearing expiry",
                payload,
                "/app/provisions/inventory",
                UUID.randomUUID(),
                UUID.randomUUID()))
        .id();
  }

  @Test
  void list_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
  }

  @Test
  void list_returnsUsersNotifications() throws Exception {
    AuthedUser user = registerUser();
    seedNotification(user.userId());

    mvc.perform(get("/api/v1/notifications").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getById_404_whenOtherUsersNotification() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser other = registerUser();
    UUID id = seedNotification(owner.userId());

    mvc.perform(get("/api/v1/notifications/{id}", id).cookie(other.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void markRead_thenIllegalReReadFromDismissed_409() throws Exception {
    AuthedUser user = registerUser();
    UUID id = seedNotification(user.userId());

    mvc.perform(post("/api/v1/notifications/{id}/dismiss", id).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISMISSED"));

    mvc.perform(post("/api/v1/notifications/{id}/read", id).cookie(user.cookie()))
        .andExpect(status().isConflict());
  }

  @Test
  void summary_reflectsCounts() throws Exception {
    AuthedUser user = registerUser();
    seedNotification(user.userId());

    mvc.perform(get("/api/v1/notifications/summary").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unreadCount").value(1))
        .andExpect(jsonPath("$.attentionCount").value(1))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void bulkRead_marksAll() throws Exception {
    AuthedUser user = registerUser();
    seedNotification(user.userId());
    seedNotification(user.userId());

    mvc.perform(
            post("/api/v1/notifications/bulk/read")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kinds\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(2));

    Long unread =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND status = 'UNREAD'",
            Long.class,
            user.userId().toString());
    assertThat(unread).isZero();
  }

  @Test
  void deliveryLog_404_whenNotificationMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/notifications/{id}/delivery-log", UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }
}
