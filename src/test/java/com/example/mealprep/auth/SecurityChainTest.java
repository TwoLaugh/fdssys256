package com.example.mealprep.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.config.AuthSecurityConfig;
import com.example.mealprep.auth.domain.repository.ServiceTokenRepository;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.internal.SessionRevoker;
import com.example.mealprep.auth.domain.service.internal.SessionTokenGenerator;
import com.example.mealprep.config.GlobalExceptionHandler;
import com.example.mealprep.core.audit.api.controller.AdminDecisionLogController;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.core.origin.OriginContext;
import com.example.mealprep.core.origin.OriginProperties;
import com.example.mealprep.core.origin.internal.InMemoryTokenBucketRateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for the deny-by-default chain wired by {@link AuthSecurityConfig}. Verifies that
 * non-whitelisted paths return 401 without a cookie and that whitelisted paths reach the
 * dispatcher.
 *
 * <p>Real {@link com.example.mealprep.auth.config.SessionAuthenticationFilter} is registered via
 * the imported {@code AuthSecurityConfig}; its dependencies are mocked. With no cookie in the
 * request, the filter takes the no-op path so the chain's authorize rules drive the response.
 */
@WebMvcTest(controllers = AdminDecisionLogController.class)
@AutoConfigureMockMvc
// AuthSecurityConfig now wires an OriginFilter (core/02b) into the chain. The filter's
// collaborators — OriginContext (request-scoped), OriginProperties, the rate limiter — are
// core.origin @Components not picked up by the @WebMvcTest slice's component scan, so import
// them explicitly. ServiceTokenRepository is a JPA repo (no slice support) → @MockBean. The
// Clock + ServiceTokenAuthenticationProvider beans come transitively from AuthSecurityConfig.
@Import({
  AuthSecurityConfig.class,
  GlobalExceptionHandler.class,
  OriginContext.class,
  InMemoryTokenBucketRateLimiter.class
})
// OriginProperties is a constructor-bound @ConfigurationProperties record; @Import can't
// instantiate it — @EnableConfigurationProperties triggers Spring Boot's binding from the
// mealprep.origin.* keys in application.properties.
@EnableConfigurationProperties(OriginProperties.class)
@ActiveProfiles("test")
class SecurityChainTest {

  @Autowired private MockMvc mvc;
  @MockBean private DecisionLogQueryService decisionLogQueryService;
  @MockBean private SessionRepository sessionRepository;
  @MockBean private UserRepository userRepository;
  @MockBean private SessionTokenGenerator sessionTokenGenerator;
  // SessionRevoker (auth-6) is a @Component the filter now depends on; not in the WebMvcTest slice
  // scan, so mock it. The no-cookie tests never reach the soft-deleted-user revoke branch anyway.
  @MockBean private SessionRevoker sessionRevoker;
  @MockBean private ServiceTokenRepository serviceTokenRepository;

  /**
   * Defensive: clear any {@link SecurityContextHolder} state an earlier test class on the same
   * fork-thread may have left behind. Surefire runs with {@code forkCount=1C} + {@code
   * reuseForks=true}, so when {@code OriginFilterTest} (or any other test that populates the
   * holder) runs first in the fork, its {@code ThreadLocal} principal leaks here unless cleared.
   * With a leaked principal the chain's {@code .anyRequest().authenticated()} passes, the request
   * reaches {@link AdminDecisionLogController#getById}, the mocked {@code DecisionLogQueryService}
   * returns an empty {@code Optional}, and the controller throws {@code
   * ResponseStatusException(NOT_FOUND)} — surfacing as a 404 where the test expects 401. The
   * upstream tests now also clear in their {@code @AfterEach}; this hook is belt-and-suspenders.
   */
  @BeforeEach
  void clearLeakedSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void protectedEndpoint_returns401_whenNoCookiePresent() throws Exception {
    mvc.perform(get("/api/v1/admin/decision-log/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void registerEndpoint_isAnonymousAccessible_andNotBlockedBySecurityChain() throws Exception {
    // AuthController is not loaded in this slice, so a whitelisted path falls through to a 404
    // from the dispatcher. The crucial thing is that the chain did NOT block with 401.
    mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void loginEndpoint_isAnonymousAccessible_andNotBlockedBySecurityChain() throws Exception {
    mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void apiDocsEndpoint_isAnonymousAccessible() throws Exception {
    // /v3/api-docs is not handled by the controllers under test, but the security chain must let
    // the request through — we get a 404 from the dispatcher, not a 401 from the chain.
    mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
  }
}
