package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.domain.service.internal.PromptTemplateRenderer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit guard over the engineered dispatch prompt files (audit item ai-1) — NO network. For
 * every chat task that is wired end-to-end today, this asserts the production prompt file:
 *
 * <ul>
 *   <li>ships on the classpath under {@code prompts/&lt;module&gt;/*.txt},
 *   <li>is <b>renderer-safe</b> — it carries no Handlebars section/inversion syntax ({@code
 *       {{#...}}} / {@code {{/...}}}) or Jinja blocks ({@code {% ... %}}) that the simple {@link
 *       PromptTemplateRenderer} cannot process (the LLD constraint: the renderer is plain {@code
 *       {{var}}} substitution only), and
 *   <li>renders cleanly against the exact variable-key set the task's {@code variables()} supplies
 *       — i.e. every {@code {{placeholder}}} in the file is a key the task passes, and the task
 *       passes no key the file silently drops in a way that would surface raw JSON to the model.
 * </ul>
 *
 * <p>This is the deterministic, gate-included counterpart to {@code PromptLiveValidationIT} (which
 * is {@code @Tag("live")} and only run on demand). It exercises the prompts without any model call.
 *
 * <p><b>Nutrition tasks deferred.</b> {@code INTAKE_PARSE} and {@code INGREDIENT_MAPPING} have no
 * {@code AiTask} / caller wired yet (only {@link com.example.mealprep.ai.spi.TaskType} entries and
 * the {@code lld/prompts/02,03-*.md} design docs the loader audits), so there is no dispatch {@code
 * .txt} to check here — that lands with their Phase-3 wiring.
 */
class EngineeredPromptFilesTest {

  private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

  @Test
  void feedbackClassificationPrompt_isRendererSafe_andRendersWithTaskKeys() {
    String tpl = load("prompts/feedback/classify-feedback.txt");
    assertRendererSafe(tpl, "feedback/classify-feedback");
    // Always-present keys from FeedbackClassificationContext.toRendererMap() that the body uses.
    String rendered =
        renderer.render(
            tpl,
            Map.of(
                "feedback_text", "this was too salty",
                "screen_context", "recipe_page",
                "recent_classifications", "[]"));
    assertThat(rendered).contains("[Task: FEEDBACK_CLASSIFICATION]");
    assertThat(rendered).contains("this was too salty");
    assertThat(rendered).doesNotContain("{{");
  }

  @Test
  void recipeAdaptationPrompt_isRendererSafe_andRendersWithTaskKeys() {
    String tpl = load("prompts/adaptation/recipe-adaptation.txt");
    assertRendererSafe(tpl, "adaptation/recipe-adaptation");
    // The 11 keys RecipeAdaptationTask.variables() supplies (all non-null via orEmpty).
    String rendered =
        renderer.render(
            tpl,
            Map.ofEntries(
                Map.entry("mode", "FEEDBACK"),
                Map.entry("recipe", "{...}"),
                Map.entry("candidates", "[...]"),
                Map.entry("softPreferences", ""),
                Map.entry("nutritionTargets", ""),
                Map.entry("knowledgeBundle", ""),
                Map.entry("feedbackText", "too salty"),
                Map.entry("ratingDelta", ""),
                Map.entry("directive", ""),
                Map.entry("dataModelChange", ""),
                Map.entry("hardConstraintsHash", "hc:none")));
    assertThat(rendered).contains("[Task: RECIPE_ADAPTATION]");
    assertThat(rendered).contains("recipe_adaptation_response");
    assertThat(rendered).doesNotContain("{{");
  }

  @Test
  void discoveryCandidateFilterPrompt_isRendererSafe_andRendersWithTaskKeys() {
    String tpl = load("prompts/discovery/candidate-filter.txt");
    assertRendererSafe(tpl, "discovery/candidate-filter");
    // The keys CandidateFilterTask.variables() supplies.
    String rendered =
        renderer.render(
            tpl,
            Map.of(
                "candidate.canonicalUrl", "https://example.com/x",
                "candidate.title", "15-minute tofu bibimbap",
                "candidate.snippet", "A weeknight Korean rice bowl.",
                "constraints.cuisines", "[Korean]",
                "constraints.dietaryFlags", "[]",
                "constraints.maxPrepMins", "30"));
    assertThat(rendered).contains("[Task: DISCOVERY_FILTERING]");
    assertThat(rendered).contains("\"relevant\"");
    assertThat(rendered).doesNotContain("{{");
  }

  @Test
  void plannerStageCPrompt_isRendererSafe_andRendersWithTaskKeys() {
    String tpl = load("prompts/planner/stage-c-pick.txt");
    assertRendererSafe(tpl, "planner/stage-c-pick");
    // The keys StageCPickTask.variables() supplies.
    String rendered =
        renderer.render(
            tpl,
            Map.of(
                "candidates", "[...]",
                "constraints_summary", "targets...",
                "household_size", 2,
                "week_start", "2026-06-01",
                "trigger", "SCHEDULED_WEEKLY"));
    assertThat(rendered).contains("[Task: PLANNER_STAGE_C]");
    assertThat(rendered).contains("stage_c_pick_response");
    assertThat(rendered).doesNotContain("{{");
  }

  @Test
  void plannerPhase2Prompt_isRendererSafe_andRendersWithTaskKeys() {
    String tpl = load("prompts/planner/phase2-augmentation.txt");
    assertRendererSafe(tpl, "planner/phase2-augmentation");
    // The keys Phase2AugmentationTask.variables() supplies.
    String rendered =
        renderer.render(
            tpl,
            Map.of(
                "chosen_plan", "{...}",
                "constraints_summary", "targets...",
                "nutrition_gaps", "[...]",
                "max_augmentations", 5,
                "max_refine_directives", 2));
    assertThat(rendered).contains("[Task: PLANNER_PHASE2_AUGMENTATION]");
    assertThat(rendered).contains("phase2_augmentation");
    assertThat(rendered).doesNotContain("{{");
  }

  @Test
  void preferenceTasteDeltaPrompt_isRendererSafe_andRendersWithTaskKeys() {
    String tpl = load("prompts/preference/taste-profile-delta-user.txt");
    assertRendererSafe(tpl, "preference/taste-profile-delta-user");
    // The always-present keys PreferenceTasteProfileDeltaTask.variables() supplies.
    String rendered =
        renderer.render(
            tpl,
            Map.of(
                "current_taste_profile", "{...}",
                "feedback_batch", "[...]",
                "recent_archive_ids", "[]"));
    assertThat(rendered).contains("[Task: PREFERENCE_DELTA_UPDATE]");
    assertThat(rendered).contains("propose_taste_profile_deltas");
    assertThat(rendered).doesNotContain("{{");
  }

  /**
   * A prompt body is renderer-safe when it contains no Handlebars section/inversion/partial markers
   * and no Jinja statement blocks — only the simple {@code {{var}}} placeholders the {@link
   * PromptTemplateRenderer} (and the LLD) support.
   */
  private static void assertRendererSafe(String template, String name) {
    assertThat(template).as("%s has no Handlebars sections", name).doesNotContain("{{#");
    assertThat(template).as("%s has no Handlebars closers", name).doesNotContain("{{/");
    assertThat(template).as("%s has no Handlebars partials", name).doesNotContain("{{>");
    assertThat(template).as("%s has no Jinja blocks", name).doesNotContain("{%");
  }

  private String load(String classpath) {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpath)) {
      assertThat(in).as("prompt file on classpath: %s", classpath).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new AssertionError("could not read prompt file " + classpath, ex);
    }
  }
}
