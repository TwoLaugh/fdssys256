package com.example.mealprep.ai.testing;

import com.example.mealprep.ai.spi.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E-only HTTP control plane for seeding the deterministic AI double ({@link TestAiService}).
 *
 * <p><b>Why this exists.</b> The end-to-end suite is a BLACK BOX (decision D2): it speaks only
 * HTTP/JSON and has no in-process access to the running app's beans. In-process ITs seed a canned
 * AI response with a Java call ({@code testAiService.register(...)}); the E2E suite cannot. So any
 * E2E flow that triggers an AI dispatch would otherwise fail with {@code
 * AiInvalidResponseException("No canned response registered...")}. This controller lets a scenario
 * seed a realistic, model-shaped JSON response over HTTP; {@link TestAiService#registerJson} then
 * deserialises it through the REAL {@code ObjectMapper} into the task's declared output type on
 * dispatch — so the flow also exercises the genuine JSON→domain wire-contract.
 *
 * <p><b>Strictly {@code e2e}-profile-gated.</b> {@code @Profile("e2e")} means the bean (and
 * therefore the {@code /test-support/ai/canned} request mapping) does NOT exist under {@code
 * prod}/{@code dev}/{@code test}. In production the path is simply an unmapped 404 — it is never a
 * live attack surface.
 *
 * <p><b>Reachability / security — no AuthSecurityConfig or OriginFilter change was needed.</b> Two
 * facts make an authenticated POST here reach the handler with the chain UNCHANGED:
 *
 * <ul>
 *   <li>{@code AuthSecurityConfig} is deny-by-default with {@code .anyRequest().authenticated()}.
 *       The E2E {@code ApiClient} auto-logs-in a cookie session (register == auto-login), so its
 *       authenticated POST satisfies that rule. {@code /test-support/**} is deliberately NOT added
 *       to the permitAll whitelist — that would weaken nothing in prod (the path 404s there) but
 *       there is no reason to make a control endpoint anonymous, and keeping it behind auth means a
 *       stray unauthenticated probe in CI gets a clean 401 rather than mutating stub state.
 *   <li>{@code OriginFilter} has a fast-path: when there is no {@code X-Origin} header it calls
 *       {@code chain.doFilter} and returns immediately (it neither inspects the path nor runs the
 *       {@code @OriginAware} annotation check). The E2E client sends no {@code X-Origin} header
 *       (USER origin), so the filter never blocks {@code /test-support/**} and no
 *       {@code @OriginAware} annotation is required on this controller.
 * </ul>
 *
 * <p>The path lives OUTSIDE {@code /api} on purpose: it is test scaffolding, not part of the
 * product API surface, and the {@code /test-support} prefix signals that to anyone reading routes.
 */
@RestController
@RequestMapping("/test-support/ai")
@Profile("e2e")
public class E2eAiStubController {

  private final TestAiService testAiService;

  public E2eAiStubController(TestAiService testAiService) {
    this.testAiService = testAiService;
  }

  /**
   * Seed a canned AI response for a task type. The {@code responseJson} is stored verbatim and
   * deserialised on each dispatch through the real {@code ObjectMapper} into the task's output
   * type.
   *
   * @return 204 No Content
   */
  @PostMapping(path = "/canned", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void seedCanned(@RequestBody CannedResponseRequest request) {
    testAiService.registerJson(request.taskType(), request.responseJson());
  }

  /**
   * Reset the stub — forget all canned responses, embeddings, and recorded calls. Useful for a
   * scenario that needs a clean stub mid-run (e.g. soak mode).
   *
   * @return 204 No Content
   */
  @DeleteMapping(path = "/canned")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clearCanned() {
    testAiService.clear();
  }

  /**
   * Request body for {@link #seedCanned}.
   *
   * @param taskType the {@link TaskType} this canned response applies to
   * @param responseJson a JSON string mirroring what the real model would emit for that task; it is
   *     deserialised into {@code task.outputType()} on dispatch
   */
  public record CannedResponseRequest(@NotNull TaskType taskType, @NotBlank String responseJson) {}
}
