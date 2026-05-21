package com.example.mealprep.core.origin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.config.AuthSecurityConfig;
import com.example.mealprep.auth.domain.entity.ServiceToken;
import com.example.mealprep.auth.domain.repository.ServiceTokenRepository;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.internal.SessionTokenGenerator;
import com.example.mealprep.auth.security.ServiceTokenAuthenticationProvider;
import com.example.mealprep.config.GlobalExceptionHandler;
import com.example.mealprep.core.api.OriginHeaders;
import com.example.mealprep.core.origin.internal.InMemoryTokenBucketRateLimiter;
import com.example.mealprep.core.origin.testdata.ServiceTokenTestData;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web-slice integration test for the {@link OriginFilter} wired into the real security chain. Uses
 * a stub {@link OriginAwareTestController} annotated {@link OriginAware} so we can assert both the
 * pass-through behaviour and the 403 on unannotated handlers (a separate stub).
 *
 * <p>No Testcontainers: the JPA / Flyway integration is covered by {@code OriginAuditMigrationIT}.
 * This test focuses on the HTTP-side behaviour of the filter. The {@link OriginContext} (request-
 * scoped), {@link InMemoryTokenBucketRateLimiter} and {@code OriginProperties} are imported
 * explicitly because {@code @WebMvcTest} doesn't component-scan them; {@code AuthSecurityConfig}
 * supplies the {@code OriginFilter} + {@code ServiceTokenAuthenticationProvider} beans + the Clock.
 */
@WebMvcTest(
    controllers = {
      OriginFilterIT.OriginAwareTestController.class,
      OriginFilterIT.PlainTestController.class
    })
@AutoConfigureMockMvc
@Import({
  AuthSecurityConfig.class,
  GlobalExceptionHandler.class,
  OriginContext.class,
  InMemoryTokenBucketRateLimiter.class
})
// OriginProperties is a constructor-bound @ConfigurationProperties record; it must be registered
// via @EnableConfigurationProperties (not @Import) so the @TestPropertySource values below bind.
@EnableConfigurationProperties(OriginProperties.class)
@TestPropertySource(
    properties = {
      "mealprep.origin.ai-confidence-floor=0.5",
      "mealprep.origin.rate-limit-window=PT1H",
      "mealprep.origin.rate-limits.AI_FEEDBACK.limit=2",
      "mealprep.origin.rate-limits.AI_FEEDBACK.scope=PER_USER",
      "mealprep.origin.reject-origin-on-non-annotated-controller=true",
      "mealprep.auth.cookie-name=AUTH_SESSION",
      "mealprep.auth.session-ttl=PT24H"
    })
@ActiveProfiles("test")
class OriginFilterIT {

  @Autowired private MockMvc mvc;

  @MockBean private SessionRepository sessionRepository;
  @MockBean private UserRepository userRepository;
  @MockBean private SessionTokenGenerator sessionTokenGenerator;
  @MockBean private ServiceTokenRepository serviceTokenRepository;

  // ------------------------------------------------------------------------------------------
  // Plain user requests: filter is a no-op when no X-Origin header is present.

  @Test
  void plain_user_request_no_origin_header_passes_through() throws Exception {
    mvc.perform(
            post("/test/origin-aware-endpoint")
                .with(
                    req -> {
                      req.setRemoteUser("test-user");
                      return req;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(authenticatedAsAnyUser()))
        .andExpect(status().isOk());
  }

  // ------------------------------------------------------------------------------------------
  // Validation rejections produce ProblemDetail responses with the right slug.

  @Test
  void unknown_origin_returns_400_problem_detail() throws Exception {
    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header(OriginHeaders.X_ORIGIN, "NOT_AN_ORIGIN")
                .with(authenticatedAsAnyUser()))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/unknown-origin"));
  }

  @Test
  void non_user_origin_missing_trace_returns_400() throws Exception {
    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confidence\": 0.9}")
                .header(OriginHeaders.X_ORIGIN, "AI_FEEDBACK")
                .with(authenticatedAsAnyUser()))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://mealprep.example.com/problems/origin-trace-required-for-non-user-origin"));
  }

  @Test
  void depth_exceeded_returns_422() throws Exception {
    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confidence\": 0.9}")
                .header(OriginHeaders.X_ORIGIN, "AI_FEEDBACK")
                .header(OriginHeaders.X_ORIGIN_TRACE, "feedback-x")
                .header(OriginHeaders.X_ORIGIN_DEPTH, "4")
                .with(authenticatedAsAnyUser()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/origin-depth-exceeded"));
  }

  @Test
  void confidence_below_threshold_returns_422() throws Exception {
    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confidence\": 0.3}")
                .header(OriginHeaders.X_ORIGIN, "AI_FEEDBACK")
                .header(OriginHeaders.X_ORIGIN_TRACE, "feedback-x")
                .with(authenticatedAsAnyUser()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/confidence-below-threshold"));
  }

  @Test
  void non_origin_aware_endpoint_rejects_non_user_origin_with_403() throws Exception {
    mvc.perform(
            post("/test/plain-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confidence\": 0.9}")
                .header(OriginHeaders.X_ORIGIN, "AI_FEEDBACK")
                .header(OriginHeaders.X_ORIGIN_TRACE, "feedback-x")
                .with(authenticatedAsAnyUser()))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/origin-not-permitted-on-endpoint"));
  }

  @Test
  void rate_limit_exhaustion_returns_429_with_retry_after_header() throws Exception {
    // Bucket is 2 per props; third call rejected.
    for (int i = 0; i < 2; i++) {
      mvc.perform(
              post("/test/origin-aware-endpoint")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"confidence\": 0.9}")
                  .header(OriginHeaders.X_ORIGIN, "AI_FEEDBACK")
                  .header(OriginHeaders.X_ORIGIN_TRACE, "feedback-" + i)
                  .with(authenticatedAsAnyUser()))
          .andExpect(status().isOk());
    }

    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confidence\": 0.9}")
                .header(OriginHeaders.X_ORIGIN, "AI_FEEDBACK")
                .header(OriginHeaders.X_ORIGIN_TRACE, "feedback-rejected")
                .with(authenticatedAsAnyUser()))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/origin-rate-limit-exceeded"));
  }

  // ------------------------------------------------------------------------------------------
  // Pattern B: bearer service token

  @Test
  void pattern_B_valid_token_for_permitted_origin_succeeds() throws Exception {
    String rawToken = "test-bearer-token";
    String hash = ServiceTokenAuthenticationProvider.sha256Hex(rawToken);
    ServiceToken token = ServiceTokenTestData.aLiveToken(hash, Origin.SYSTEM_SCHEDULED);
    when(serviceTokenRepository.findByTokenHashAndEnabledTrueAndRevokedAtIsNull(hash))
        .thenReturn(Optional.of(token));

    UUID actingAs = UUID.randomUUID();

    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer " + rawToken)
                .header(OriginHeaders.X_ORIGIN, "SYSTEM_SCHEDULED")
                .header(OriginHeaders.X_ORIGIN_TRACE, "scheduled-x")
                .header(OriginHeaders.X_ACTING_AS, actingAs.toString()))
        .andExpect(status().isOk());
  }

  @Test
  void pattern_B_invalid_token_returns_401() throws Exception {
    when(serviceTokenRepository.findByTokenHashAndEnabledTrueAndRevokedAtIsNull(anyString()))
        .thenReturn(Optional.empty());

    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer ghost-token")
                .header(OriginHeaders.X_ORIGIN, "SYSTEM_SCHEDULED")
                .header(OriginHeaders.X_ORIGIN_TRACE, "scheduled-x")
                .header(OriginHeaders.X_ACTING_AS, UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pattern_B_token_with_wrong_permitted_origin_returns_403() throws Exception {
    String rawToken = "scheduled-only-token";
    String hash = ServiceTokenAuthenticationProvider.sha256Hex(rawToken);
    ServiceToken token = ServiceTokenTestData.aLiveToken(hash, Origin.SYSTEM_SCHEDULED);
    when(serviceTokenRepository.findByTokenHashAndEnabledTrueAndRevokedAtIsNull(hash))
        .thenReturn(Optional.of(token));

    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confidence\": 0.9}")
                .header("Authorization", "Bearer " + rawToken)
                .header(OriginHeaders.X_ORIGIN, "AI_FEEDBACK")
                .header(OriginHeaders.X_ORIGIN_TRACE, "feedback-x")
                .header(OriginHeaders.X_ACTING_AS, UUID.randomUUID().toString()))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/origin-not-permitted-by-token"));
  }

  @Test
  void pattern_B_missing_acting_as_returns_400() throws Exception {
    mvc.perform(
            post("/test/origin-aware-endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer some-token")
                .header(OriginHeaders.X_ORIGIN, "SYSTEM_SCHEDULED")
                .header(OriginHeaders.X_ORIGIN_TRACE, "scheduled-x"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/acting-as-required-under-pattern-b"));
  }

  // ------------------------------------------------------------------------------------------
  // The test controllers & helpers.

  /**
   * Helper: attach an arbitrary authenticated user to the SecurityContext for the slice request.
   * Pattern A flows still need the chain to consider the request authenticated.
   */
  private static org.springframework.test.web.servlet.request.RequestPostProcessor
      authenticatedAsAnyUser() {
    return req -> {
      org.springframework.security.core.context.SecurityContextHolder.getContext()
          .setAuthentication(
              new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                  new com.example.mealprep.auth.domain.service.AuthenticatedPrincipal(
                      UUID.randomUUID(), UUID.randomUUID()),
                  null,
                  java.util.List.of(
                      new org.springframework.security.core.authority.SimpleGrantedAuthority(
                          "ROLE_USER"))));
      return req;
    };
  }

  /** Test controller method marked {@link OriginAware}. */
  @RestController
  static class OriginAwareTestController {
    @OriginAware
    @PostMapping("/test/origin-aware-endpoint")
    public String handle() {
      return "ok";
    }
  }

  /** Plain test controller — not annotated → non-USER origin should be rejected 403. */
  @RestController
  static class PlainTestController {
    @PostMapping("/test/plain-endpoint")
    public String handle() {
      return "ok";
    }

    @PostMapping("/test/echo")
    public String echo() {
      return "ok";
    }
  }
}
