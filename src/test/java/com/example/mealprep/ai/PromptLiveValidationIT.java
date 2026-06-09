package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.config.AiTokenCapProperties;
import com.example.mealprep.ai.config.OpenAiChatProperties;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.domain.service.internal.AiCallRecorder;
import com.example.mealprep.ai.domain.service.internal.AiServiceImpl;
import com.example.mealprep.ai.domain.service.internal.ChatClient;
import com.example.mealprep.ai.domain.service.internal.CostBudgetGuard;
import com.example.mealprep.ai.domain.service.internal.CostCalculator;
import com.example.mealprep.ai.domain.service.internal.OpenAiChatClient;
import com.example.mealprep.ai.domain.service.internal.OpenAiEmbeddingClient;
import com.example.mealprep.ai.domain.service.internal.StructuredOutputParser;
import com.example.mealprep.ai.domain.service.internal.TokenCapGuard;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMatchResult;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMatchTask;
import com.example.mealprep.nutrition.domain.service.internal.IngredientParseResult;
import com.example.mealprep.nutrition.domain.service.internal.IngredientParseTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Opt-in, single-run LIVE validation of the engineered chat prompts (audit item ai-1) — EXCLUDED
 * from the blocking gate. For every chat task that is <b>wired end-to-end today</b> it builds a
 * production-shaped {@link AiTask} (carrying the real {@code variables()} the calling module would
 * pass — <b>no</b> pre-rendered {@code "prompt"} string) and dispatches it through the REAL {@link
 * AiService#execute} path. That path renders the production {@code prompts/<module>/*.txt} file
 * from the task's {@link TaskType} (the ai-1 wiring) and sends it through the REAL {@link
 * OpenAiChatClient} (provider = openai, key from {@code OPENAI_API_KEY}). It asserts (a) the
 * structured output is schema-valid against the task's own {@link ToolDefinition} (or, for the
 * free-text discovery filter, parses to the right shape) and (b) a basic sanity check (an
 * obviously-negative feedback routes to the expected destination; a clear non-recipe roundup is
 * rejected; etc.).
 *
 * <p><b>Why through {@code AiService.execute} now (not the client directly).</b> The point of ai-1
 * is that production dispatch renders the engineered file; routing this harness through the real
 * dispatcher validates that exact wiring rather than re-implementing the render in the test. The
 * {@link AiServiceImpl} is built with {@code new} (no Spring context); its DB-touching
 * collaborators ({@link AiCallRecorder}, {@link CostBudgetGuard}) are Mockito no-ops, the token-cap
 * guard is real but disabled, and the {@link ChatClient} is a real {@link OpenAiChatClient} over a
 * live {@link OpenAIClient}. So the {@code TestAiService} stub is irrelevant here and the normal
 * gate still makes ZERO live calls.
 *
 * <p><b>Call budget — one full run is ~14 calls, all on the cheapest tier the task allows:</b>
 * feedback ×2, recipe-adaptation ×2, discovery-filter ×2, planner Stage-C ×2, planner Phase-2 ×1,
 * preference taste-delta ×2, nutrition ingredient-parse ×1, nutrition ingredient-match ×2.
 *
 * <p><b>Tasks NOT covered (live validation deferred):</b> {@code INTAKE_PARSE} and {@code
 * INGREDIENT_MAPPING} have no {@code AiTask} implementation or caller yet (only the {@link
 * TaskType} enum entries and the {@code lld/prompts/02,03-*.md} design docs). Their live validation
 * is deferred to their Phase-3 wiring; the loader already audits their {@code .md} bodies.
 *
 * <p><b>Why it is gate-excluded.</b> Tagged {@link Tag @Tag("live")}; the Surefire / Failsafe
 * config in {@code pom.xml} sets {@code <excludedGroups>${test.excludedGroups}</excludedGroups>}
 * (default {@code live}), so {@code ./mvnw test} / {@code ./mvnw verify} never runs it.
 *
 * <p><b>How to run it on demand (the orchestrator runs this once, not the build agent):</b>
 *
 * <pre>{@code # whole harness (~11 calls)
 * OPENAI_API_KEY=sk-... \
 *   ./mvnw verify -Dgroups=live -Dtest.excludedGroups= -Dit.test=PromptLiveValidationIT
 *
 * # a SINGLE task's cases only (cheap targeted re-run), e.g. just feedback classification:
 * OPENAI_API_KEY=sk-... \
 *   ./mvnw verify -Dgroups=live -Dtest.excludedGroups= \
 *     -Dit.test=PromptLiveValidationIT#feedbackClassification_routesNegativeRecipeFeedback}</pre>
 *
 * Each task is its own {@code @Test} method, so {@code -Dit.test=PromptLiveValidationIT#<method>}
 * runs just that task's 1-2 calls. Every method self-skips via an assumption when {@code
 * OPENAI_API_KEY} is unset, so a key-less run does not hard-fail.
 */
@Tag("live")
class PromptLiveValidationIT {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final StructuredOutputParser parser = new StructuredOutputParser(objectMapper);

  // --- Feedback classification (CHEAP) ------------------------------------------------------

  @Test
  void feedbackClassification_routesNegativeRecipeFeedback() {
    AiService ai = liveService();

    // Recipe-specific complaint pinned to the recipe page -> should classify to RECIPE.
    JsonNode out =
        ai.execute(
            task(
                TaskType.FEEDBACK_CLASSIFICATION,
                ModelTier.CHEAP,
                new PromptRef("feedback/classify-feedback", 1),
                Map.of(
                    "feedback_text", "This stir fry was way too salty",
                    "screen_context", "recipe_page",
                    "recent_classifications", List.of()),
                Optional.of(
                    List.of(
                        com.example.mealprep.feedback.domain.service.internal.ToolDefinitions
                            .classifyFeedback()))));

    JsonNode classifications = out.get("classifications");
    assertThat(classifications).as("classifications array present").isNotNull();
    assertThat(classifications.isArray()).isTrue();
    assertThat(classifications).as("non-empty for a clear single-aspect complaint").isNotEmpty();
    assertThat(classifications.get(0).get("destination").asText()).isEqualTo("RECIPE");

    // General taste statement -> PREFERENCE, never RECIPE.
    JsonNode pref =
        ai.execute(
            task(
                TaskType.FEEDBACK_CLASSIFICATION,
                ModelTier.CHEAP,
                new PromptRef("feedback/classify-feedback", 1),
                Map.of(
                    "feedback_text", "I generally don't like coriander in anything",
                    "screen_context", "general",
                    "recent_classifications", List.of()),
                Optional.of(
                    List.of(
                        com.example.mealprep.feedback.domain.service.internal.ToolDefinitions
                            .classifyFeedback()))));
    JsonNode prefClassifications = pref.get("classifications");
    assertThat(prefClassifications).isNotEmpty();
    assertThat(prefClassifications.get(0).get("destination").asText()).isEqualTo("PREFERENCE");
  }

  // --- Discovery candidate filter (CHEAP, free-text JSON, no tool schema) --------------------

  @Test
  void discoveryFilter_keepsRecipe_rejectsRoundup() {
    AiService ai = liveService();

    // A clear single recipe within constraints -> relevant = true.
    JsonNode recipe =
        ai.execute(
            task(
                TaskType.DISCOVERY_FILTERING,
                ModelTier.CHEAP,
                new PromptRef("discovery/candidate-filter", 1),
                Map.of(
                    "candidate.canonicalUrl",
                    "https://example.com/15-min-tofu-bibimbap",
                    "candidate.title",
                    "15-minute tofu bibimbap",
                    "candidate.snippet",
                    "A weeknight take on the Korean rice bowl with crispy tofu and quick-pickled veg.",
                    "constraints.cuisines",
                    List.of("Korean", "Japanese"),
                    "constraints.dietaryFlags",
                    List.of(),
                    "constraints.maxPrepMins",
                    30),
                Optional.empty()));
    assertThat(recipe.get("relevant").asBoolean()).isTrue();
    assertThat(recipe.get("confidence").asDouble()).isBetween(0.0, 1.0);
    assertThat(recipe.get("reason").asText()).isNotBlank();

    // A roundup listicle -> relevant = false.
    JsonNode roundup =
        ai.execute(
            task(
                TaskType.DISCOVERY_FILTERING,
                ModelTier.CHEAP,
                new PromptRef("discovery/candidate-filter", 1),
                Map.of(
                    "candidate.canonicalUrl", "https://example.com/30-quick-mediterranean-dinners",
                    "candidate.title", "30 quick Mediterranean dinners for busy weeknights",
                    "candidate.snippet",
                        "Lemon chicken, sheet-pan salmon, chickpea stew and 27 more easy ideas.",
                    "constraints.cuisines", List.of("Mediterranean"),
                    "constraints.dietaryFlags", List.of(),
                    "constraints.maxPrepMins", "n/a"),
                Optional.empty()));
    assertThat(roundup.get("relevant").asBoolean()).isFalse();
  }

  // --- Recipe adaptation (MID) --------------------------------------------------------------

  @Test
  void recipeAdaptation_picksMinimalChange_andSignalsNoChange() {
    AiService ai = liveService();
    ToolDefinition tool = adaptationTool();

    // Clear minimal-change pick: complaint is saltiness; candidate 0 reduces salt directly.
    JsonNode pick =
        ai.execute(
            task(
                TaskType.RECIPE_ADAPTATION,
                ModelTier.MID,
                new PromptRef("RecipeAdaptationTask", 1),
                adaptationVars(
                    "FEEDBACK",
                    "Chicken stir-fry with soy sauce, garlic, ginger, peppers.",
                    "[{\"index\":0,\"change\":\"reduce soy sauce by 30%\"},"
                        + "{\"index\":1,\"change\":\"swap soy for tamari and add lime, chilli, sesame\"}]",
                    "User feedback: too salty"),
                Optional.of(List.of(tool))));
    assertThat(pick.get("chosenCandidateIndex").asInt()).isEqualTo(0);
    assertThat(pick.get("classification").asText()).isNotBlank();
    assertThat(pick.get("confidence").asDouble()).isBetween(0.0, 1.0);
    assertThat(pick.get("characterPreservationScore").asDouble()).isBetween(0.0, 1.0);

    // No candidate fits -> NO_CHANGE with chosenCandidateIndex = -1.
    JsonNode noChange =
        ai.execute(
            task(
                TaskType.RECIPE_ADAPTATION,
                ModelTier.MID,
                new PromptRef("RecipeAdaptationTask", 1),
                adaptationVars(
                    "IMPORT",
                    "A well-formed imported margherita pizza recipe with no issues.",
                    "[{\"index\":0,\"change\":\"replace the entire base and sauce with a calzone\"}]",
                    "Import normalisation; the recipe is already clean."),
                Optional.of(List.of(tool))));
    assertThat(noChange.get("chosenCandidateIndex").asInt()).isEqualTo(-1);
    assertThat(noChange.get("classification").asText()).isEqualTo("NO_CHANGE");
  }

  // --- Planner Stage C (MID) ----------------------------------------------------------------

  @Test
  void plannerStageC_picksInRangeIndex() {
    AiService ai = liveService();
    ToolDefinition tool = stageCTool();

    String candidates =
        "[{\"index\":0,\"perDay\":[{\"date\":\"2026-06-01\",\"calories\":1500,\"proteinG\":60}]},"
            + "{\"index\":1,\"perDay\":[{\"date\":\"2026-06-01\",\"calories\":2000,\"proteinG\":120}]}]";

    JsonNode out =
        ai.execute(
            task(
                TaskType.PLANNER_STAGE_C,
                ModelTier.MID,
                new PromptRef("planner/stage-c-pick", 1),
                Map.of(
                    "constraints_summary",
                        "Targets: ~2000 kcal/day, >=100g protein/day. No allergens.",
                    "household_size", 2,
                    "week_start", "2026-06-01",
                    "trigger", "SCHEDULED_WEEKLY",
                    "candidates", candidates),
                Optional.of(List.of(tool))));
    int idx = out.get("chosenIndex").asInt();
    assertThat(idx).as("chosenIndex within candidate range").isBetween(0, 1);
    assertThat(out.get("reasoning").asText()).isNotBlank();

    // Second, slightly different shaping to confirm stable structured output.
    JsonNode out2 =
        ai.execute(
            task(
                TaskType.PLANNER_STAGE_C,
                ModelTier.MID,
                new PromptRef("planner/stage-c-pick", 1),
                Map.of(
                    "constraints_summary", "Vegetarian household; protein target 90g/day.",
                    "household_size", 3,
                    "week_start", "2026-06-08",
                    "trigger", "MID_WEEK_REOPT",
                    "candidates", candidates),
                Optional.of(List.of(tool))));
    assertThat(out2.get("chosenIndex").asInt()).isBetween(0, 1);
  }

  // --- Planner Phase 2 augmentation (HIGH) --------------------------------------------------

  @Test
  void plannerPhase2_returnsBoundedProposals() {
    AiService ai = liveService();

    JsonNode out =
        ai.execute(
            task(
                TaskType.PLANNER_PHASE2_AUGMENTATION,
                ModelTier.HIGH,
                new PromptRef("planner/phase2-augmentation", 1),
                Map.of(
                    "constraints_summary",
                    "Protein target 120g/day; budget moderate; no shellfish.",
                    "chosen_plan",
                    "{\"slots\":[{\"slotId\":\"s1\",\"recipe\":\"porridge\"},"
                        + "{\"slotId\":\"s2\",\"recipe\":\"pasta\"}]}",
                    "nutrition_gaps",
                    "[{\"date\":\"2026-06-01\",\"macro\":\"protein\",\"actual\":80,"
                        + "\"target\":120,\"direction\":\"under\"}]",
                    "max_augmentations",
                    5,
                    "max_refine_directives",
                    2),
                Optional.of(List.of(phase2Tool()))));
    JsonNode augs = out.get("augmentations");
    JsonNode dirs = out.get("refineDirectives");
    assertThat(augs).isNotNull();
    assertThat(augs.isArray()).isTrue();
    assertThat(augs.size()).isLessThanOrEqualTo(5);
    assertThat(dirs).isNotNull();
    assertThat(dirs.size()).isLessThanOrEqualTo(2);
  }

  // --- Preference taste-profile delta (MID) -------------------------------------------------

  @Test
  void preferenceTasteDelta_addsExplicit_skipsOneOff() {
    AiService ai = liveService();
    ToolDefinition tool = com.example.mealprep.feedback.ai.task.PreferenceDeltaToolDefinition.get();

    String profile =
        "{\"likes\":{\"cuisines\":[],\"ingredients\":[],\"cooking_methods\":[],\"flavour_notes\":[]},"
            + "\"dislikes\":{\"cuisines\":[],\"ingredients\":[],\"cooking_methods\":[],"
            + "\"flavour_notes\":[]},\"experiments\":{\"hypotheses\":[]},\"archive\":[]}";

    // Explicit positive single statement -> at least one Add.
    JsonNode add =
        ai.execute(
            task(
                TaskType.PREFERENCE_DELTA_UPDATE,
                ModelTier.MID,
                new PromptRef("preference/taste-profile-delta-user", 1),
                Map.of(
                    "current_taste_profile",
                    profile,
                    "feedback_batch",
                    "[{\"feedbackId\":\"f1\",\"userText\":\"I've decided I really love prawns in"
                        + " stir fries\",\"classifierConfidence\":0.92}]",
                    "recent_archive_ids",
                    "[]"),
                Optional.of(List.of(tool))));
    assertThat(add.get("deltas")).isNotNull();
    assertThat(add.get("deltas").isArray()).isTrue();
    assertThat(add.get("deltas")).as("explicit positive warrants at least one delta").isNotEmpty();
    assertThat(add.get("overallReasoning").asText()).isNotBlank();

    // One-off "too salty" on one dish -> empty deltas (three-event rule).
    JsonNode oneOff =
        ai.execute(
            task(
                TaskType.PREFERENCE_DELTA_UPDATE,
                ModelTier.MID,
                new PromptRef("preference/taste-profile-delta-user", 1),
                Map.of(
                    "current_taste_profile",
                    profile,
                    "feedback_batch",
                    "[{\"feedbackId\":\"f1\",\"userText\":\"this was a bit too salty\","
                        + "\"contextSummary\":\"one chicken stir fry\",\"classifierConfidence\":0.7}]",
                    "recent_archive_ids",
                    "[]"),
                Optional.of(List.of(tool))));
    assertThat(oneOff.get("deltas").isEmpty())
        .as("a single one-off 'too salty' should not warrant a delta")
        .isTrue();
  }

  // --- Nutrition ingredient parse (CHEAP, nutrition-01k) ------------------------------------

  @Test
  void nutritionIngredientParse_cleansSearchTerm() {
    AiService ai = liveService();

    // A messy line with quantity + prep — the model should strip those and return a clean,
    // searchable food term (no nutrition values), driving the downstream USDA/OFF search.
    IngredientParseResult out =
        ai.execute(
            new IngredientParseTask(
                "2 cups all-purpose flour, sifted", "all-purpose flour", null, null));

    assertThat(out).isNotNull();
    assertThat(out.searchTermOrNull()).as("a usable USDA search term").isNotBlank();
    assertThat(out.usdaSearchTerm().toLowerCase()).as("still about flour").contains("flour");
    assertThat(out.confidence()).isNotNull();
    assertThat(out.confidence().doubleValue()).isBetween(0.0, 1.0);
  }

  // --- Nutrition ingredient match (CHEAP, nutrition-01k) ------------------------------------

  @Test
  void nutritionIngredientMatch_picksBestCandidate_andCanDecline() {
    AiService ai = liveService();

    // Candidate 1 is the raw chicken breast the term names -> the model should pick index 1.
    IngredientMatchResult pick =
        ai.execute(
            new IngredientMatchTask(
                "2 chicken breasts",
                "chicken breast",
                List.of(
                    new IngredientMatchTask.Candidate("USDA", "u-1", "Beef, ground, raw"),
                    new IngredientMatchTask.Candidate(
                        "USDA", "u-2", "Chicken, broilers or fryers, breast, meat only, raw"),
                    new IngredientMatchTask.Candidate("OFF", "o-3", "Chicken nuggets, frozen")),
                null,
                null));
    assertThat(pick.chosenIndex()).as("picks the raw chicken breast").isEqualTo(1);
    assertThat(pick.confidenceOrZero()).isBetween(0.0, 1.0);

    // None of the candidates is basil — the model must stay schema-valid (an in-range index or
    // -1 "no good match"), never an out-of-range/hallucinated index.
    IngredientMatchResult hard =
        ai.execute(
            new IngredientMatchTask(
                "fresh basil leaves",
                "basil",
                List.of(
                    new IngredientMatchTask.Candidate("USDA", "u-9", "Beef, ground, raw"),
                    new IngredientMatchTask.Candidate("USDA", "u-10", "Sugar, granulated")),
                null,
                null));
    assertThat(hard.chosenIndex())
        .as("schema-valid: -1 (decline) or an in-range index, never out of range")
        .isBetween(-1, 1);
    assertThat(hard.confidenceOrZero()).isBetween(0.0, 1.0);
  }

  // ==========================================================================================
  // Helpers
  // ==========================================================================================

  /**
   * A real {@link AiServiceImpl} over a live {@link OpenAiChatClient}, with the DB-touching
   * collaborators mocked away. This is the production dispatch path: {@code execute} renders the
   * engineered prompt file from the task's {@link TaskType}, sends it to OpenAI, validates the
   * structured output, and deserialises it into {@code task.outputType()} ({@link JsonNode} here).
   * Self-skips when {@code OPENAI_API_KEY} is unset.
   */
  private AiService liveService() {
    String apiKey = System.getenv("OPENAI_API_KEY");
    Assumptions.assumeTrue(
        apiKey != null && !apiKey.isBlank(),
        "OPENAI_API_KEY unset — skipping the live prompt validation");

    OpenAIClient openAiClient = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
    AiProperties properties =
        new AiProperties(
            null,
            null,
            modelFor(ModelTier.CHEAP),
            modelFor(ModelTier.MID),
            modelFor(ModelTier.HIGH),
            60,
            2,
            apiKey,
            null,
            null);

    ChatClient chatClient =
        new OpenAiChatClient(
            singleProvider(openAiClient),
            properties,
            objectMapper,
            parser,
            CircuitBreakerRegistry.ofDefaults());

    // DB-touching collaborators are no-ops; we are validating the render + provider path only.
    AiCallRecorder recorder = mock(AiCallRecorder.class);
    when(recorder.recordPending(any(), any(), any())).thenReturn(UUID.randomUUID());
    CostBudgetGuard budgetGuard = mock(CostBudgetGuard.class); // checkOrThrow is a no-op

    // Real token-cap guard, disabled so a large representative prompt is never rejected.
    TokenCapGuard tokenCapGuard =
        new TokenCapGuard(new AiTokenCapProperties(false, null, null, null), objectMapper);

    return new AiServiceImpl(
        chatClient,
        mock(OpenAiEmbeddingClient.class),
        recorder,
        mock(ApplicationEventPublisher.class),
        properties,
        objectMapper,
        Clock.systemUTC(),
        budgetGuard,
        tokenCapGuard,
        new CostCalculator());
  }

  /**
   * Per-tier model id: env override (OPENAI_{CHEAP,MID,HIGH}_MODEL), else the config placeholder.
   */
  private String modelFor(ModelTier tier) {
    String env =
        switch (tier) {
          case CHEAP -> System.getenv("OPENAI_CHEAP_MODEL");
          case MID -> System.getenv("OPENAI_MID_MODEL");
          case HIGH -> System.getenv("OPENAI_HIGH_MODEL");
        };
    if (env != null && !env.isBlank()) {
      return env;
    }
    return new OpenAiChatProperties(null, null).openai().modelIdFor(tier);
  }

  /**
   * A production-shaped {@link AiTask}: it carries ONLY the real context {@code variables()} (no
   * pre-rendered {@code "prompt"} key), so {@code AiService.execute} must render the engineered
   * file for the given {@link TaskType}. Output type is {@link JsonNode} so the dispatcher's
   * deserialise step yields a tree the assertions can read.
   */
  private static AiTask<JsonNode> task(
      TaskType type,
      ModelTier tier,
      PromptRef ref,
      Map<String, Object> vars,
      Optional<List<ToolDefinition>> tools) {
    return new AiTask<>() {
      @Override
      public TaskType type() {
        return type;
      }

      @Override
      public ModelTier tier() {
        return tier;
      }

      @Override
      public PromptRef prompt() {
        return ref;
      }

      @Override
      @SuppressWarnings("unchecked")
      public Class<JsonNode> outputType() {
        return (Class<JsonNode>) (Class<?>) JsonNode.class;
      }

      @Override
      public Map<String, Object> variables() {
        return vars;
      }

      @Override
      public Optional<List<ToolDefinition>> tools() {
        return tools;
      }

      @Override
      public Optional<UUID> userId() {
        return Optional.empty();
      }

      @Override
      public Optional<UUID> traceId() {
        return Optional.empty();
      }
    };
  }

  private static Map<String, Object> adaptationVars(
      String mode, String recipe, String candidates, String triggerContext) {
    // All 11 keys the RecipeAdaptationTask passes; trigger-specific fields blank when not relevant.
    return Map.ofEntries(
        Map.entry("mode", mode),
        Map.entry("recipe", recipe),
        Map.entry("candidates", candidates),
        Map.entry("softPreferences", ""),
        Map.entry("nutritionTargets", ""),
        Map.entry("knowledgeBundle", ""),
        Map.entry("feedbackText", triggerContext),
        Map.entry("ratingDelta", ""),
        Map.entry("directive", ""),
        Map.entry("dataModelChange", ""),
        Map.entry("hardConstraintsHash", "hc:none"));
  }

  private ToolDefinition adaptationTool() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("chosenCandidateIndex").put("type", "integer");
    props.putObject("classification").put("type", "string");
    props.putObject("reasoning").put("type", "string");
    props.putObject("nutritionalNotes").put("type", "string");
    props.putObject("confidence").put("type", "number");
    props.putObject("characterPreservationScore").put("type", "number");
    schema
        .putArray("required")
        .add("chosenCandidateIndex")
        .add("classification")
        .add("reasoning")
        .add("confidence")
        .add("characterPreservationScore");
    return new ToolDefinition(
        "recipe_adaptation_response", "Structured recipe-adaptation decision.", schema);
  }

  private ToolDefinition stageCTool() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("chosenIndex").put("type", "integer");
    props.putObject("reasoning").put("type", "string");
    schema.putArray("required").add("chosenIndex").add("reasoning");
    return new ToolDefinition(
        "stage_c_pick_response", "The chosen candidate index and a brief reasoning.", schema);
  }

  /**
   * Mirrors {@code Phase2ToolDefinitions} (package-private, not reachable from this package): a
   * {@code augmentations} array (oneOf ADD_SNACK / INGREDIENT_SWAP / REPAIR) and a {@code
   * refineDirectives} array (oneOf SUBSTITUTE_INGREDIENT / REDUCE_TIME).
   */
  private ToolDefinition phase2Tool() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");

    ObjectNode augmentations = props.putObject("augmentations");
    augmentations.put("type", "array");
    augmentations.put("minItems", 0);
    augmentations.put("maxItems", 5);
    ObjectNode aug = augmentations.putObject("items");
    aug.put("type", "object");
    aug.putArray("oneOf")
        .add(phase2Variant("ADD_SNACK", "targetSlotId", "newRecipeId", "servings"))
        .add(
            phase2Variant(
                "INGREDIENT_SWAP", "targetSlotId", "fromIngredientKey", "toIngredientKey"))
        .add(phase2Variant("REPAIR", "targetSlotId", "issue", "resolution"));

    ObjectNode directives = props.putObject("refineDirectives");
    directives.put("type", "array");
    directives.put("minItems", 0);
    directives.put("maxItems", 2);
    ObjectNode dir = directives.putObject("items");
    dir.put("type", "object");
    dir.putArray("oneOf")
        .add(
            phase2Variant(
                "SUBSTITUTE_INGREDIENT", "targetSlotId", "fromIngredientKey", "toIngredientKey"))
        .add(phase2Variant("REDUCE_TIME", "targetSlotId", "currentTimeMin", "targetTimeMin"));

    schema.putArray("required").add("augmentations").add("refineDirectives");
    return new ToolDefinition(
        "phase2_augmentation",
        "Up to 5 plan augmentations plus up to 2 refine-directives improving the chosen plan.",
        schema);
  }

  private ObjectNode phase2Variant(String type, String f1, String f2, String f3) {
    ObjectNode v = objectMapper.createObjectNode();
    v.put("type", "object");
    ObjectNode p = v.putObject("properties");
    p.putObject("type").put("type", "string").putArray("enum").add(type);
    p.putObject(f1).put("type", "string");
    p.putObject(f2).put("type", "string");
    p.putObject(f3).put("type", "string");
    p.putObject("reasoning").put("type", "string");
    v.putArray("required").add("type");
    return v;
  }

  private static ObjectProvider<OpenAIClient> singleProvider(OpenAIClient client) {
    return new ObjectProvider<>() {
      @Override
      public OpenAIClient getObject() {
        return client;
      }

      @Override
      public OpenAIClient getObject(Object... args) {
        return client;
      }

      @Override
      public OpenAIClient getIfAvailable() {
        return client;
      }

      @Override
      public OpenAIClient getIfUnique() {
        return client;
      }
    };
  }
}
