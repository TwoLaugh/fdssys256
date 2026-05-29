package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.exception.AiCircuitOpenException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiRateLimitException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP adapter over Anthropic's Messages API, with Resilience4j circuit breaking + classified retry
 * per {@code lld/ai.md} Flow 2.
 *
 * <p><b>Resilience approach — programmatic, not annotation-driven.</b> The breaker is obtained from
 * a {@link CircuitBreakerRegistry} keyed {@code ai-${taskType}} and the retry is the existing
 * in-loop backoff driven by {@link RetryPolicy}. Programmatic Resilience4j is used deliberately
 * over Spring-AOP {@code @CircuitBreaker}/{@code @Retry} annotations because:
 *
 * <ul>
 *   <li><b>No self-invocation trap.</b> AOP advice only fires when the call crosses the Spring
 *       proxy; this class drives its own retry loop and parse step in one method, so an annotation
 *       on {@code call(...)} would never see the per-attempt {@code post(...)} invocations. The
 *       codebase otherwise works around this with the {@code @Lazy} self-proxy pattern
 *       (PlanComposer / AdaptationServiceImpl); programmatic decoration sidesteps it entirely.
 *   <li><b>Unit-testability.</b> {@link AnthropicClientTest} and {@code AiMutationKillsTest}
 *       construct this class with {@code new} (no Spring context, no proxy). Annotations would be
 *       inert there. The registry is a plain constructor arg, so the same {@code new} construction
 *       drives the real breaker — the resilience path is exercised by fast unit tests, which is the
 *       gate for finding {@code ai-2} (TestAiService stubs dispatch in e2e/module ITs).
 *   <li><b>In-repo precedent.</b> {@code SourceRateLimiterRegistry} (discovery) already builds
 *       Resilience4j primitives programmatically rather than via annotations.
 * </ul>
 *
 * <p><b>Retry classification.</b> Per attempt: HTTP 5xx / {@link IOException} → {@code TIMEOUT}
 * (short backoff, retried); HTTP 429 → {@code RATE_LIMIT} (longer backoff, retried — the {@code
 * ai-2} fix); HTTP 401/403 and other 4xx → fatal {@link AiInvalidRequestException}, never retried.
 * When the breaker is open the call short-circuits without touching the wire and throws {@link
 * AiCircuitOpenException} (mapped to 503).
 *
 * <p>The API key is set per-request via the {@code x-api-key} header — never logged, never bound to
 * the {@link RestClient} bean. {@code anthropic-version} is pinned per Anthropic docs.
 */
@Component
public class AnthropicClient {

  private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

  /** Pinned per Anthropic Messages API docs; bumped only on a deliberate version sweep. */
  static final String ANTHROPIC_VERSION = "2023-06-01";

  /** Default {@code max_tokens} sent on every request — small enough to bound runaway cost. */
  static final int DEFAULT_MAX_TOKENS = 1024;

  /** Open the breaker after this many failures in the sliding window (lld/ai.md Flow 2). */
  static final int CIRCUIT_FAILURE_THRESHOLD = 5;

  /** Sliding window (count-based) over which the failure threshold is evaluated. */
  static final int CIRCUIT_WINDOW_SIZE = 5;

  /** How long the breaker stays OPEN before allowing a half-open probe (lld/ai.md Flow 2). */
  static final Duration CIRCUIT_OPEN_DURATION = Duration.ofMinutes(5);

  private final RestClient restClient;
  private final AiProperties properties;
  private final ObjectMapper objectMapper;
  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private Sleeper sleeper;

  public AnthropicClient(
      RestClient anthropicRestClient,
      AiProperties properties,
      ObjectMapper objectMapper,
      CircuitBreakerRegistry circuitBreakerRegistry) {
    this.restClient = anthropicRestClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.circuitBreakerRegistry = circuitBreakerRegistry;
    this.sleeper = Thread::sleep;
  }

  /** Test seam — replaces the production {@code Thread::sleep} so retries don't block tests. */
  public void setSleeper(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  /**
   * Build the per-task-type circuit-breaker config: open after {@value #CIRCUIT_FAILURE_THRESHOLD}
   * failures in a count-based window of {@value #CIRCUIT_WINDOW_SIZE}, stay open for {@link
   * #CIRCUIT_OPEN_DURATION}, then allow a single half-open probe.
   */
  public static CircuitBreakerConfig circuitBreakerConfig() {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(CIRCUIT_WINDOW_SIZE)
        .minimumNumberOfCalls(CIRCUIT_FAILURE_THRESHOLD)
        // failureRateThreshold is a percentage; 100% means "open only when the whole window
        // failed",
        // i.e. CIRCUIT_FAILURE_THRESHOLD consecutive failures over a window of equal size.
        .failureRateThreshold(100.0f)
        .waitDurationInOpenState(CIRCUIT_OPEN_DURATION)
        .permittedNumberOfCallsInHalfOpenState(1)
        .automaticTransitionFromOpenToHalfOpenEnabled(false)
        // Only transient failures count against the breaker; a fatal 4xx caller-bug should not trip
        // it (those are the caller's problem, not an upstream-health signal).
        .recordExceptions(AiUnavailableException.class, ResourceAccessException.class)
        .ignoreExceptions(AiInvalidRequestException.class)
        .build();
  }

  /** Resolve (creating on first use) the breaker for a task type, keyed {@code ai-${taskType}}. */
  CircuitBreaker breakerFor(AiTask<?> task) {
    return circuitBreakerRegistry.circuitBreaker("ai-" + task.type(), circuitBreakerConfig());
  }

  /**
   * Make a Messages call through the task-type circuit breaker, with classified retry.
   *
   * <p>Retries HTTP 5xx / {@link IOException} ({@code TIMEOUT}) and HTTP 429 ({@code RATE_LIMIT},
   * longer backoff); never retries genuine 4xx caller-bugs.
   *
   * @throws AiCircuitOpenException the task-type breaker is open — short-circuited without a wire
   *     call.
   * @throws AiInvalidRequestException 4xx response (caller error), incl. AUTH 401/403.
   * @throws AiRateLimitException retries exhausted on HTTP 429.
   * @throws AiUnavailableException retries exhausted on transient (5xx / IO) failures.
   */
  public AnthropicResponse call(AiTask<?> task, String modelId) {
    CircuitBreaker breaker = breakerFor(task);
    if (!breaker.tryAcquirePermission()) {
      // Breaker OPEN (or half-open quota spent) — short-circuit WITHOUT hitting the wire.
      log.warn("ai circuit OPEN for {} — short-circuiting call", breaker.getName());
      throw new AiCircuitOpenException(
          "AI circuit open for " + breaker.getName() + "; call short-circuited");
    }
    long start = System.nanoTime();
    try {
      AnthropicResponse response = callWithRetry(task, modelId);
      breaker.onSuccess(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      return response;
    } catch (AiInvalidRequestException fatal) {
      // Caller-bug 4xx (incl. AUTH) — ignored by the breaker config, but report it so the
      // permission accounting is balanced; it does not count toward opening.
      breaker.onError(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, fatal);
      throw fatal;
    } catch (AiUnavailableException transientExhausted) {
      // Transient exhaustion (incl. AiRateLimitException) — counts toward opening the breaker.
      breaker.onError(
          System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, transientExhausted);
      throw transientExhausted;
    } catch (RuntimeException unexpected) {
      breaker.onError(
          System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, unexpected);
      throw unexpected;
    }
  }

  /**
   * The classified retry loop. Retries {@code TIMEOUT} (5xx / IO) and {@code RATE_LIMIT} (429);
   * surfaces a fatal 4xx immediately. Each retryable category gets its own backoff base via {@link
   * RetryPolicy#backoffFor}.
   */
  private AnthropicResponse callWithRetry(AiTask<?> task, String modelId) {
    String requestBody = buildRequestBody(task, modelId);
    int maxAttempts = Math.max(1, properties.maxRetries());
    AiUnavailableException lastTransient = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        String responseBody = post(requestBody);
        return parse(responseBody);
      } catch (AiInvalidRequestException fatal) {
        // 4xx caller-bug (incl. AUTH 401/403) — no retry; surface immediately.
        throw fatal;
      } catch (AiRateLimitException rateLimited) {
        lastTransient = rateLimited;
        if (attempt == maxAttempts) {
          break;
        }
        long delay = RetryPolicy.backoffFor(RetryPolicy.Category.RATE_LIMIT, attempt).toMillis();
        log.warn(
            "anthropic RATE_LIMIT (429) (attempt {}/{}), backing off {}ms before retry: {}",
            attempt,
            maxAttempts,
            delay,
            rateLimited.getMessage());
        sleepQuietly(delay);
      } catch (AiUnavailableException | ResourceAccessException transientEx) {
        lastTransient =
            transientEx instanceof AiUnavailableException u
                ? u
                : new AiUnavailableException(transientEx.getMessage(), transientEx);
        if (attempt == maxAttempts) {
          break;
        }
        long delay = RetryPolicy.backoffFor(RetryPolicy.Category.TIMEOUT, attempt).toMillis();
        log.warn(
            "anthropic call failed (attempt {}/{}), retrying after {}ms: {}",
            attempt,
            maxAttempts,
            delay,
            transientEx.getMessage());
        sleepQuietly(delay);
      }
    }
    if (lastTransient instanceof AiRateLimitException) {
      throw new AiRateLimitException(
          "Anthropic rate-limited (429) after " + maxAttempts + " attempts", lastTransient);
    }
    throw new AiUnavailableException(
        "Anthropic call failed after " + maxAttempts + " attempts", lastTransient);
  }

  private String post(String requestBody) {
    return restClient
        .post()
        .uri("/v1/messages")
        .contentType(MediaType.APPLICATION_JSON)
        .header("x-api-key", properties.anthropicApiKey())
        .header("anthropic-version", ANTHROPIC_VERSION)
        .body(requestBody)
        .exchange(
            (request, response) -> {
              int code = response.getStatusCode().value();
              String body = readBody(response);
              if (code >= 200 && code < 300) {
                return body;
              }
              RetryPolicy.Category category = RetryPolicy.classifyStatus(code);
              switch (category) {
                case RATE_LIMIT -> {
                  log.warn("anthropic returned 429 RATE_LIMIT bodyExcerpt={}", excerpt(body));
                  throw new AiRateLimitException("Anthropic 429 RATE_LIMIT: " + excerpt(body));
                }
                case AUTH -> {
                  log.warn("anthropic returned AUTH status={} bodyExcerpt={}", code, excerpt(body));
                  throw new AiInvalidRequestException(
                      "Anthropic auth failure (" + code + "): " + excerpt(body));
                }
                case UNKNOWN -> {
                  log.warn("anthropic returned 4xx status={} bodyExcerpt={}", code, excerpt(body));
                  throw new AiInvalidRequestException(
                      "Anthropic 4xx (" + code + "): " + excerpt(body));
                }
                default -> {
                  // TIMEOUT — 5xx (and any unexpected non-2xx below 400).
                  log.warn("anthropic returned 5xx status={} bodyExcerpt={}", code, excerpt(body));
                  throw new AiUnavailableException(
                      "Anthropic 5xx (" + code + "): " + excerpt(body));
                }
              }
            },
            false);
  }

  /**
   * Build the Messages-API request body. We translate {@link AiTask#variables()} and {@link
   * AiTask#tools()} into Anthropic's wire shape; for 01a the rendered prompt is the JSON of {@code
   * variables()} so the calling module can ship a one-shot template that doesn't yet need the
   * file-backed renderer (01d).
   */
  public String buildRequestBody(AiTask<?> task, String modelId) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("model", modelId);
    root.put("max_tokens", DEFAULT_MAX_TOKENS);

    ArrayNode messages = root.putArray("messages");
    ObjectNode userMsg = messages.addObject();
    userMsg.put("role", "user");
    userMsg.put("content", renderUserMessage(task));

    task.tools()
        .filter(t -> !t.isEmpty())
        .ifPresent(
            tools -> {
              ArrayNode toolsArray = root.putArray("tools");
              for (ToolDefinition def : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("name", def.name());
                if (def.description() != null) {
                  toolNode.put("description", def.description());
                }
                toolNode.set("input_schema", def.inputSchema());
              }
            });
    try {
      return objectMapper.writeValueAsString(root);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      // ObjectNode -> String can fail only on a programming error.
      throw new IllegalStateException("Failed to serialise Anthropic request body", ex);
    }
  }

  private String renderUserMessage(AiTask<?> task) {
    Map<String, Object> vars = task.variables();
    if (vars == null || vars.isEmpty()) {
      // The prompt-template loader (01d) materialises the body; until then the calling module is
      // expected to put the rendered prompt under the conventional "prompt" variable.
      return task.prompt().name();
    }
    Object explicit = vars.get("prompt");
    if (explicit instanceof String s && !s.isBlank()) {
      return s;
    }
    try {
      return objectMapper.writeValueAsString(vars);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialise prompt variables", ex);
    }
  }

  /**
   * Extract response payload. Concatenates {@code content[].text} blocks; if a {@code tool_use}
   * block is present, its {@code input} is serialised and used instead — the calling task expects
   * structured JSON when it specifies tools.
   */
  public AnthropicResponse parse(String responseBody) {
    JsonNode root;
    try {
      root = objectMapper.readTree(responseBody);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new AiUnavailableException("Anthropic response was not JSON", ex);
    }
    String modelId = root.path("model").asText(null);
    Integer requestTokens = optionalInt(root.path("usage").path("input_tokens"));
    Integer responseTokens = optionalInt(root.path("usage").path("output_tokens"));

    JsonNode content = root.path("content");
    if (!content.isArray() || content.isEmpty()) {
      return new AnthropicResponse("", requestTokens, responseTokens, modelId);
    }
    StringBuilder text = new StringBuilder();
    for (JsonNode block : content) {
      String type = block.path("type").asText("");
      if ("tool_use".equals(type)) {
        JsonNode input = block.path("input");
        if (input.isObject() || input.isArray()) {
          return new AnthropicResponse(input.toString(), requestTokens, responseTokens, modelId);
        }
      } else if ("text".equals(type)) {
        text.append(block.path("text").asText(""));
      }
    }
    return new AnthropicResponse(text.toString(), requestTokens, responseTokens, modelId);
  }

  private static Integer optionalInt(JsonNode node) {
    return node != null && node.isInt() ? node.asInt() : null;
  }

  private static String excerpt(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 256 ? body : body.substring(0, 256) + "...";
  }

  private static String readBody(org.springframework.http.client.ClientHttpResponse response) {
    try {
      return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      return "<unreadable: " + ex.getMessage() + ">";
    }
  }

  private void sleepQuietly(long ms) {
    try {
      sleeper.sleep(ms);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AiUnavailableException("Interrupted during retry backoff", ex);
    }
  }

  /** Test seam — production wires {@link Thread#sleep(long)}. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(long ms) throws InterruptedException;
  }
}
