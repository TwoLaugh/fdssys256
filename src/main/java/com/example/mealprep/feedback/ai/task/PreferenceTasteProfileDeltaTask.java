package com.example.mealprep.feedback.ai.task;

import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.feedback.ai.dto.ClassifiedFeedbackEvent;
import com.example.mealprep.feedback.ai.dto.TasteProfileDeltaResponse;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mid-tier {@link AiTask} that reads a user's current taste profile + a batch of PREFERENCE-routed
 * feedback + recent archive keys and proposes a batch of {@link TasteProfileDeltaResponse}. The
 * only AI path that may write to the taste profile (via the deterministic 01f applier downstream).
 *
 * <p>Implements {@code lld/prompts/01-taste-profile-delta.md} — the skeleton there (lines 358-386)
 * predates the merged {@link AiTask} SPI shape, so this task mirrors the <b>real</b> SPI ({@code
 * type/tier/prompt/outputType/variables/tools/userId/traceId}) rather than the doc's {@code
 * getSystemPrompt()/getContext()/getTimeoutOverride()} draft. The system prompt + 15s timeout are
 * carried as renderer variables ({@code system_prompt}, {@code timeout_seconds}) so they reach the
 * dispatcher without an SPI change.
 *
 * <p>Created fresh per orchestrator run (carries call-scoped context); not a Spring bean.
 */
public final class PreferenceTasteProfileDeltaTask implements AiTask<TasteProfileDeltaResponse> {

  /** Prompt name handed to the renderer; resolved against the prompt-template service. */
  public static final String PROMPT_NAME = "preference/taste-profile-delta-user";

  /** Prompt version owned by the feedback module (bumped when prompt 01 ships a v2). */
  public static final int PROMPT_VERSION = 1;

  /** Per-task timeout override (15s) per the prompt skeleton. */
  public static final int TIMEOUT_SECONDS = 15;

  private final TasteProfileDocument currentProfile;
  private final List<ClassifiedFeedbackEvent> feedbackBatch;
  private final List<String> recentArchiveIds;
  private final UUID userId;
  private final UUID traceId;
  private final String correctiveHint;

  public PreferenceTasteProfileDeltaTask(
      TasteProfileDocument currentProfile,
      List<ClassifiedFeedbackEvent> feedbackBatch,
      List<String> recentArchiveIds,
      UUID userId,
      UUID traceId,
      String correctiveHint) {
    if (currentProfile == null) {
      throw new IllegalArgumentException("currentProfile must not be null");
    }
    if (userId == null) {
      throw new IllegalArgumentException("userId must not be null");
    }
    this.currentProfile = currentProfile;
    this.feedbackBatch = feedbackBatch == null ? List.of() : List.copyOf(feedbackBatch);
    this.recentArchiveIds = recentArchiveIds == null ? List.of() : List.copyOf(recentArchiveIds);
    this.userId = userId;
    this.traceId = traceId;
    this.correctiveHint = correctiveHint;
  }

  @Override
  public TaskType type() {
    return TaskType.PREFERENCE_DELTA_UPDATE;
  }

  @Override
  public ModelTier tier() {
    // Taste-profile delta generation is the mid-tier (Sonnet) slot per the prompt §Wiring.
    return ModelTier.MID;
  }

  @Override
  public PromptRef prompt() {
    return new PromptRef(PROMPT_NAME, PROMPT_VERSION);
  }

  @Override
  public Class<TasteProfileDeltaResponse> outputType() {
    return TasteProfileDeltaResponse.class;
  }

  @Override
  public Map<String, Object> variables() {
    Map<String, Object> map = new HashMap<>();
    map.put("system_prompt", SYSTEM_PROMPT);
    map.put("timeout_seconds", TIMEOUT_SECONDS);
    map.put("current_taste_profile", currentProfile);
    map.put("feedback_batch", feedbackBatch);
    map.put("recent_archive_ids", recentArchiveIds);
    map.put("user_id", userId);
    if (correctiveHint != null && !correctiveHint.isBlank()) {
      map.put("corrective_hint", correctiveHint);
    }
    return map;
  }

  @Override
  public Optional<List<ToolDefinition>> tools() {
    return Optional.of(List.of(PreferenceDeltaToolDefinition.get()));
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.of(userId);
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(traceId);
  }

  /** Verbatim system prompt from {@code lld/prompts/01-taste-profile-delta.md:100-147}. */
  public static final String SYSTEM_PROMPT =
      """
      You are a careful editor of a user's taste profile, a JSON document representing their food \
      preferences. Each call you receive: (1) the current profile, (2) a batch of recent feedback \
      the classifier routed to you. Your job: produce a list of well-formed delta operations that \
      refine the profile based on what the feedback genuinely tells you.

      You are NOT the only signal feeding this profile. The user can edit it directly. You should \
      be conservative — better to skip a delta than to make a wrong one. Wrong deltas accumulate \
      as bad recommendations downstream.

      THE PROFILE'S STRUCTURE (read carefully):
      - likes.cuisines, likes.ingredients, likes.cooking_methods, likes.flavour_notes
      - dislikes.cuisines, dislikes.ingredients, dislikes.cooking_methods, dislikes.flavour_notes
      - experiments.hypotheses[]: things the system is currently testing about the user (e.g. \
      "user might like bitter flavours when paired with sweet")
      - archive: items previously in likes/dislikes that the user moved away from; archive entries \
      can be re-promoted

      EACH DELTA OP:
      - Add — add a new item to a likes/dislikes list. Use when feedback introduces a preference \
      not yet captured.
      - Remove — remove an item from a list. Use sparingly — usually you want Archive (preserves \
      history) over Remove.
      - Update — modify the notes on an existing item without changing the item itself. Use when \
      feedback adds nuance to an existing preference.
      - Archive — move an item from a list into the archive. Use when feedback indicates the user \
      has moved away from a preference (not just one-off dislike).
      - RePromote — bring an archived item back into a list. Use when feedback indicates \
      re-emergence of a preference.
      - PromoteExperiment — convert a hypothesis from experiments into a confirmed like/dislike. \
      Use when feedback aligns with the hypothesis.
      - DiscardExperiment — remove a hypothesis as disproven. Use when feedback contradicts the \
      hypothesis.
      - UpdateNotes — modify free-text notes on a section of the profile (e.g. "summary of this \
      user's flavour preferences"). Rare; use when feedback warrants a meta-level annotation.

      WHAT FEEDBACK USUALLY DOES NOT WARRANT A DELTA:
      - One-off "this dish was too salty" → could be the recipe, the cook, the user's mood that \
      day. Do NOT add "salty" to dislikes from a single event.
      - Feedback referencing an experiment without aligning with the hypothesis → leave the \
      experiment alone.
      - Mood/health complaints ("felt sluggish after") → not preference; the classifier shouldn't \
      have routed this here, but if it did, return empty deltas with a warning.
      - Comments about cost, time, equipment → not preference (those route to Provisions/\
      Lifestyle); return empty deltas with a warning.

      CONFIDENCE FOR ADD OPS:
      - HIGH: clear, repeated signal across multiple feedback events; or a single very explicit \
      statement ("I've decided I really like coriander").
      - MEDIUM: single explicit statement that doesn't reference a one-off context.
      - LOW: inferential ("they cleaned the plate" → likely positive but not stated). Often skip \
      rather than Add at LOW.

      THE THREE-EVENT RULE:
      For Add of new likes/dislikes: only act when at least 2-3 events agree, OR a single explicit \
      statement is made. Single ambiguous events → consider Update (add nuance to existing item) \
      or empty (no delta).

      ARCHIVE VS REMOVE:
      Default to Archive. Remove only when the user explicitly says "delete that" or when the item \
      was clearly added in error.

      EVIDENCE AND REASONING:
      Every delta cites the specific feedback event that drove it (`evidenceFeedbackId`). The \
      `reasoning` is one short sentence the user might read in the version history — write it as a \
      justification, not an internal note.

      THREE-EVENT RULE EDGE CASE: when a single feedback event is so explicit it warrants a \
      HIGH-confidence Add ("I've stopped eating dairy"), apply but flag in warnings. The user \
      should review.

      NUMERICAL LIMITS:
      - Maximum 50 deltas per response (locked in lld/preference.md).
      - Maximum 3 archive ops per batch — if more warranted, surface in warnings; the user reviews \
      before applying.
      - Maximum 1 UpdateNotes op per response.""";
}
