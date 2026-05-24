package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.restassured.response.Response;
import java.util.Map;

/**
 * Steps + reusable helper for priming the deterministic AI double over HTTP.
 *
 * <p>The E2E suite is a black box (decision D2) and cannot call {@code TestAiService} in-process,
 * so any flow that triggers an AI dispatch must first seed a canned response via the {@code
 * e2e}-profile-only control endpoint ({@code POST /test-support/ai/canned}, served by {@code
 * E2eAiStubController}). The request is made on THIS scenario's authenticated session (the cookie
 * jar already carries the session minted by the "fresh registered and logged-in user" step), which
 * satisfies the deny-by-default security chain.
 *
 * <p>This class offers two entry points:
 *
 * <ul>
 *   <li>{@link #primeAi(TaskType, String)} — a reusable instance method other step classes can call
 *       directly (PicoContainer injects the SAME {@link ScenarioContext} into every glue class, so
 *       a domain step can take {@code AiStubSteps} as a constructor parameter and call this);
 *   <li>{@link #theAiWillReturnThisResponse(String, String)} — a Cucumber step that takes the task
 *       type as a {@code {word}} and the canned JSON as a doc-string, for use straight from a
 *       feature file.
 * </ul>
 *
 * <p>No glue is wired here for a feature that does not yet exist — the preference feature (a
 * separate task) will author the {@code .feature} that exercises these.
 */
public class AiStubSteps {

  private final ScenarioContext context;

  public AiStubSteps(ScenarioContext context) {
    this.context = context;
  }

  /**
   * Seed a canned AI response for {@code type}: POST the {@code json} to the e2e stub endpoint on
   * this scenario's authenticated session and assert it was accepted (204). Reusable by other step
   * classes that need to prime the AI before triggering a flow.
   *
   * @param type the task type the canned response applies to
   * @param json a JSON string mirroring what the real model would emit; the app deserialises it
   *     into the task's output type on dispatch
   */
  public void primeAi(TaskType type, String json) {
    Response response =
        context
            .api()
            .post("/test-support/ai/canned", Map.of("taskType", type.name(), "responseJson", json));
    assertThat(response.statusCode())
        .as("seeding a canned AI response should return 204 No Content")
        .isEqualTo(204);
    context.setLastResponse(response);
  }

  /**
   * Reset the AI stub — forget all canned responses and recorded calls. Handy for a scenario that
   * needs a clean stub mid-run (e.g. soak mode). Asserts 204.
   */
  public void resetAiStub() {
    Response response = context.api().request().when().delete("/test-support/ai/canned");
    assertThat(response.statusCode())
        .as("resetting the AI stub should return 204 No Content")
        .isEqualTo(204);
    context.setLastResponse(response);
  }

  /**
   * Cucumber step: prime the AI to return the given canned JSON for a task type before the flow
   * that triggers the dispatch runs. The {@code {word}} is the {@link TaskType} name (e.g. {@code
   * PREFERENCE_DELTA_UPDATE}); the doc-string is the JSON body.
   *
   * <pre>{@code
   * Given the AI will return this PREFERENCE_DELTA_UPDATE response:
   *   """
   *   { "deltas": [ ... ], "overallReasoning": "...", "warnings": [] }
   *   """
   * }</pre>
   */
  @Given("the AI will return this {word} response:")
  public void theAiWillReturnThisResponse(String taskType, String json) {
    primeAi(TaskType.valueOf(taskType), json);
  }
}
