package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP adapter over Anthropic's Messages API. Hand-rolled retry: 3 attempts, exponential backoff
 * (200 / 400 / 800 ms). 4xx is never retried; 5xx and {@link IOException}-style failures are.
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

  /** Initial retry delay; doubled per attempt. */
  static final long INITIAL_BACKOFF_MS = 200L;

  private final RestClient restClient;
  private final AiProperties properties;
  private final ObjectMapper objectMapper;
  private Sleeper sleeper;

  public AnthropicClient(
      RestClient anthropicRestClient, AiProperties properties, ObjectMapper objectMapper) {
    this.restClient = anthropicRestClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.sleeper = Thread::sleep;
  }

  /** Test seam — replaces the production {@code Thread::sleep} so retries don't block tests. */
  public void setSleeper(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  /**
   * Make a Messages call. Retries 5xx and {@link IOException}; never retries 4xx.
   *
   * @throws AiInvalidRequestException 4xx response (caller error).
   * @throws AiUnavailableException retries exhausted on transient failures.
   */
  public AnthropicResponse call(AiTask<?> task, String modelId) {
    String requestBody = buildRequestBody(task, modelId);
    int maxAttempts = Math.max(1, properties.maxRetries());
    RuntimeException lastTransient = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        String responseBody = post(requestBody);
        return parse(responseBody);
      } catch (AiInvalidRequestException fatal) {
        // 4xx — no retry; surface immediately.
        throw fatal;
      } catch (AiUnavailableException | ResourceAccessException transientEx) {
        lastTransient = transientEx;
        if (attempt == maxAttempts) {
          break;
        }
        long delay = INITIAL_BACKOFF_MS << (attempt - 1);
        log.warn(
            "anthropic call failed (attempt {}/{}), retrying after {}ms: {}",
            attempt,
            maxAttempts,
            delay,
            transientEx.getMessage());
        sleepQuietly(delay);
      }
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
              if (code >= 400 && code < 500) {
                log.warn("anthropic returned 4xx status={} bodyExcerpt={}", code, excerpt(body));
                throw new AiInvalidRequestException(
                    "Anthropic 4xx (" + code + "): " + excerpt(body));
              }
              log.warn("anthropic returned 5xx status={} bodyExcerpt={}", code, excerpt(body));
              throw new AiUnavailableException("Anthropic 5xx (" + code + "): " + excerpt(body));
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
