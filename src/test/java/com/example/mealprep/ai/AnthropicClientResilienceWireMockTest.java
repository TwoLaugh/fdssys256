package com.example.mealprep.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.api.AiExceptionHandler;
import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.service.internal.AnthropicClient;
import com.example.mealprep.ai.domain.service.internal.AnthropicResponse;
import com.example.mealprep.ai.exception.AiCircuitOpenException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiRateLimitException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

/**
 * WireMock-backed resilience tests for {@link AnthropicClient} — the gate for finding {@code ai-2}.
 *
 * <p>These exercise the <em>real</em> dispatch/resilience path over a real HTTP socket. They are
 * the coverage gate because {@code TestAiService} (the {@code @Profile} swap used by e2e and
 * AI-using module ITs) returns canned responses and never reaches {@link AnthropicClient}.
 *
 * <p>Covers, per the LLD Flow 2 spec: 429 retried-with-backoff-then-succeeds; 5xx retried
 * (preserved behaviour); repeated transient failure opens the breaker and the next call
 * short-circuits without a wire hit, mapping to 503; half-open recovery after the open window; and
 * a genuine 4xx / 401-auth failing fast without retry.
 */
class AnthropicClientResilienceWireMockTest {

  private static final String MESSAGES_PATH = "/v1/messages";
  private static final String OK_BODY =
      "{\"model\":\"claude-haiku-4-5-20251001\","
          + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
          + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private WireMockServer wm;
  private CircuitBreakerRegistry registry;
  private AnthropicClient client;
  private List<Long> sleeps;

  @BeforeEach
  void setUp() {
    wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wm.start();
    WireMock.configureFor("localhost", wm.port());

    AiProperties properties =
        new AiProperties(
            "secret-key",
            "http://localhost:" + wm.port(),
            "haiku",
            "sonnet",
            "opus",
            60,
            3, // maxRetries
            null,
            null,
            null);
    // Pin the JDK HttpClient to HTTP/1.1: by default it negotiates HTTP/2, and against WireMock's
    // plaintext HTTP/1.1 server that upgrade attempt makes the POST hit the wire twice — which
    // would
    // double the request count and skip a retry/backoff. Production talks HTTPS to
    // api.anthropic.com
    // (HTTP/2-capable) and is unaffected; this is purely a test-harness concern.
    java.net.http.HttpClient http1 =
        java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();
    RestClient restClient =
        RestClient.builder()
            .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(http1))
            .baseUrl(properties.anthropicBaseUrl())
            .build();
    registry = CircuitBreakerRegistry.ofDefaults();
    client = new AnthropicClient(restClient, properties, objectMapper, registry);
    // Capture backoff delays instead of sleeping; lets us assert the rate-limit base is longer.
    sleeps = new ArrayList<>();
    client.setSleeper(sleeps::add);
  }

  @AfterEach
  void tearDown() {
    wm.stop();
  }

  /** Each test uses a distinct task type so its breaker is independent of the others. */
  private AiTask<String> task(TaskType type) {
    return AiTestData.task(String.class).ofType(type).withTier(ModelTier.CHEAP).build();
  }

  // ---------------------------------------------------------------------------------------------
  // 429 RATE_LIMIT — retried with (longer) backoff → eventually succeeds.
  // ---------------------------------------------------------------------------------------------

  @Test
  void rateLimited429_isRetriedWithLongerBackoff_thenSucceeds() {
    // Two 429s then a 200, scripted via a WireMock scenario.
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .inScenario("429-retry")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate_limited\"}"))
            .willSetStateTo("one"));
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .inScenario("429-retry")
            .whenScenarioStateIs("one")
            .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate_limited\"}"))
            .willSetStateTo("two"));
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .inScenario("429-retry")
            .whenScenarioStateIs("two")
            .willReturn(aResponse().withStatus(200).withBody(OK_BODY)));

    AnthropicResponse response = client.call(task(TaskType.FEEDBACK_CLASSIFICATION), "haiku");

    assertThat(response.body()).isEqualTo("ok");
    // Three attempts in total: 429, 429, 200.
    verify(3, postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
    // Two backoff sleeps, both on the RATE_LIMIT base (1000ms) → 1000, 2000 (longer than the
    // 200/400 transient series — this is the ai-2 correctness fix).
    assertThat(sleeps).containsExactly(1_000L, 2_000L);
  }

  // ---------------------------------------------------------------------------------------------
  // 5xx — retried (preserve existing behaviour) → eventually succeeds.
  // ---------------------------------------------------------------------------------------------

  @Test
  void serverError5xx_isRetried_thenSucceeds_withTransientBackoff() {
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .inScenario("5xx-retry")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(503).withBody("upstream down"))
            .willSetStateTo("one"));
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .inScenario("5xx-retry")
            .whenScenarioStateIs("one")
            .willReturn(aResponse().withStatus(200).withBody(OK_BODY)));

    AnthropicResponse response = client.call(task(TaskType.RECIPE_HTML_EXTRACTION), "haiku");

    assertThat(response.body()).isEqualTo("ok");
    verify(2, postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
    // One transient backoff at the 200ms base — preserves the historical 200/400 series.
    assertThat(sleeps).containsExactly(200L);
  }

  // ---------------------------------------------------------------------------------------------
  // Repeated failures → circuit OPENS → next call short-circuits (no wire hit) → 503.
  // ---------------------------------------------------------------------------------------------

  @Test
  void repeatedFailures_openCircuit_thenNextCallShortCircuits_andMapsTo503() {
    // Always 503. maxRetries=3 → each call() makes 3 wire attempts then throws AiUnavailable.
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .willReturn(aResponse().withStatus(503).withBody("down")));

    TaskType type = TaskType.PLANNER_PHASE2_AUGMENTATION;
    CircuitBreaker breaker =
        registry.circuitBreaker("ai-" + type, AnthropicClient.circuitBreakerConfig());

    // Drive 5 failed calls — each records one onError against the count-based window of 5.
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> client.call(task(type), "haiku"))
          .isInstanceOf(AiUnavailableException.class);
    }
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    int requestsBeforeOpenCall = wm.getAllServeEvents().size();

    // Next call: breaker is OPEN → short-circuit WITHOUT touching the wire →
    // AiCircuitOpenException.
    AiCircuitOpenException circuitOpen =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> client.call(task(type), "haiku"), AiCircuitOpenException.class);
    assertThat(circuitOpen).isNotNull();
    // No additional wire request was made — the breaker short-circuited in-process.
    assertThat(wm.getAllServeEvents()).hasSize(requestsBeforeOpenCall);

    // The handler maps AiCircuitOpenException → 503 with the ai-circuit-open slug.
    AiExceptionHandler handler = new AiExceptionHandler();
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRequestURI("/api/v1/ai/dispatch");
    ResponseEntity<ProblemDetail> resp = handler.handleAiCircuitOpen(circuitOpen, req);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().getType()).asString().endsWith("ai-circuit-open");
  }

  // ---------------------------------------------------------------------------------------------
  // Half-open recovery: after the open window elapses, a probe is permitted and success closes it.
  // We avoid a real 5-minute wait by transitioning the breaker to half-open programmatically (the
  // same transition the wait-duration timer performs); the value of the wait is asserted separately
  // in the unit test for circuitBreakerConfig().
  // ---------------------------------------------------------------------------------------------

  @Test
  void halfOpen_probeSuccess_closesCircuit_andCallSucceeds() {
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .willReturn(aResponse().withStatus(200).withBody(OK_BODY)));

    TaskType type = TaskType.INGREDIENT_MAPPING;
    CircuitBreaker breaker =
        registry.circuitBreaker("ai-" + type, AnthropicClient.circuitBreakerConfig());
    breaker.transitionToOpenState();
    // Simulate the open-window timer elapsing → half-open, permitting one probe.
    breaker.transitionToHalfOpenState();
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

    AnthropicResponse response = client.call(task(type), "haiku");

    assertThat(response.body()).isEqualTo("ok");
    // A successful half-open probe closes the breaker.
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  // ---------------------------------------------------------------------------------------------
  // Genuine 4xx — fail fast, never retried, NOT counted by the breaker.
  // ---------------------------------------------------------------------------------------------

  @Test
  void badRequest400_failsFast_notRetried() {
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad\"}")));

    assertThatThrownBy(() -> client.call(task(TaskType.PREFERENCE_DELTA_UPDATE), "haiku"))
        .isInstanceOf(AiInvalidRequestException.class);

    // Exactly one wire attempt — no retry on a caller-bug 4xx.
    verify(1, postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
    assertThat(sleeps).isEmpty();
  }

  @Test
  void auth401_failsFast_asInvalidRequest_notRetried_andDoesNotTripBreaker_andReleasesPermit() {
    // First call 401, then a 200 — proves the breaker permission acquired before the fatal 4xx is
    // released (via onError) so a subsequent call can still acquire one and succeed.
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .inScenario("401-then-ok")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"auth\"}"))
            .willSetStateTo("ok"));
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .inScenario("401-then-ok")
            .whenScenarioStateIs("ok")
            .willReturn(aResponse().withStatus(200).withBody(OK_BODY)));

    TaskType type = TaskType.RECIPE_ADAPTATION;
    CircuitBreaker breaker =
        registry.circuitBreaker("ai-" + type, AnthropicClient.circuitBreakerConfig());

    assertThatThrownBy(() -> client.call(task(type), "haiku"))
        .isInstanceOf(AiInvalidRequestException.class);

    // AUTH 4xx is ignored by the breaker config — it must NOT count as a failure toward opening,
    // and its permission must have been released so the breaker still permits the next call.
    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

    // The permit was released → a follow-up call acquires a fresh permit and succeeds. (If the
    // onError call in the 4xx branch were removed, the half-open/count accounting would drift; the
    // success here also confirms the 4xx neither retried nor blocked subsequent traffic.)
    AnthropicResponse ok = client.call(task(type), "haiku");
    assertThat(ok.body()).isEqualTo("ok");
    assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    verify(2, postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
    // The 4xx fails fast (no retry/backoff); the success needs none either.
    assertThat(sleeps).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // 429 retries exhausted → surfaces AiRateLimitException (a subtype of AiUnavailableException).
  // ---------------------------------------------------------------------------------------------

  @Test
  void rateLimited429_retriesExhausted_surfacesAiRateLimitException() {
    stubFor(
        post(urlPathEqualTo(MESSAGES_PATH))
            .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate_limited\"}")));

    assertThatThrownBy(() -> client.call(task(TaskType.INTAKE_PARSE), "haiku"))
        .isInstanceOf(AiRateLimitException.class)
        .isInstanceOf(AiUnavailableException.class);

    // maxRetries=3 → three wire attempts, two backoff sleeps on the rate-limit base.
    verify(3, postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
    assertThat(sleeps).containsExactly(1_000L, 2_000L);
  }
}
