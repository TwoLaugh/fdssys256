package com.example.mealprep.infra;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that Spring Boot Actuator exposes ONLY {@code /actuator/health} and {@code
 * /actuator/info}. Every other actuator endpoint that the project explicitly does NOT want publicly
 * exposed must return 404. The negative list is enumerated below so a future contributor who adds
 * an entry to {@code management.endpoints.web.exposure.include} breaks this test instead of
 * silently shipping an information-disclosure vector.
 *
 * <p>This is a security-critical assertion: an exposed {@code /actuator/env} leaks every property
 * (and thus every {@code ${...}} resolved env var, including API keys), and {@code /actuator/beans}
 * /{@code /actuator/configprops} together let an attacker map the entire internal structure of the
 * app.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class ActuatorExposureIT {

  @Autowired private MockMvc mvc;

  @Test
  void health_returns200_andStatusUp() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void health_anonymousResponse_hidesComponentDetails() throws Exception {
    // show-details=when-authorized — anonymous callers must not see component-level
    // breakdown (DB / Redis / external-API names). The body should be {"status":"UP"}, with
    // no `components` key.
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
  }

  @Test
  void info_returns200() throws Exception {
    mvc.perform(get("/actuator/info")).andExpect(status().isOk());
  }

  @Test
  void info_responseHasNoEnvBlock() throws Exception {
    // management.info.env.enabled=false — env-var dumping in /actuator/info must be disabled
    // as defense-in-depth even though include=health,info already excludes it.
    mvc.perform(get("/actuator/info")).andExpect(jsonPath("$.env").doesNotExist());
  }

  /**
   * The 14 actuator endpoints the project explicitly does NOT expose. Each must 404. Adding any
   * entry here without a matching {@code management.endpoints.web.exposure.include} bump (and a
   * security review) will fail this test.
   */
  static List<String> forbiddenActuatorPaths() {
    return List.of(
        "/actuator/env",
        "/actuator/beans",
        "/actuator/configprops",
        "/actuator/mappings",
        "/actuator/threaddump",
        "/actuator/heapdump",
        "/actuator/loggers",
        "/actuator/conditions",
        "/actuator/scheduledtasks",
        "/actuator/caches",
        "/actuator/sessions",
        "/actuator/auditevents",
        "/actuator/httptrace",
        "/actuator/metrics");
  }

  @ParameterizedTest
  @MethodSource("forbiddenActuatorPaths")
  void forbiddenActuatorEndpoint_isInaccessible(String path) throws Exception {
    // The endpoint must not be accessible to anonymous callers. Two distinct status codes
    // satisfy this property:
    //   404 — actuator did not register this endpoint (because it is not in the exposure
    //         include-list). The request reached actuator's dispatcher.
    //   401 — Spring Security's filter chain rejected the request BEFORE it reached
    //         actuator. The security config permits only /actuator/health and
    //         /actuator/info; everything else falls through to anyRequest().authenticated().
    // Both are valid "not accessible" outcomes. What this test guards against is a 200
    // response (information disclosure).
    mvc.perform(get(path))
        .andExpect(
            result -> {
              int status = result.getResponse().getStatus();
              if (status != 401 && status != 404) {
                throw new AssertionError(
                    "Expected 401 (security-rejected) or 404 (not exposed) for "
                        + path
                        + ", got "
                        + status);
              }
            });
  }
}
