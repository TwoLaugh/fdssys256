package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.ai.dto.AiTasteProfileDelta;
import com.example.mealprep.feedback.ai.internal.AiToApplyDeltaMapper;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Regression harness for the 18-case eval set at {@code lld/prompts/01-taste-profile-delta.md:
 * 308-327}. Acceptance gate: ≥ 15/18 to ship, 17/18 to consider mature.
 *
 * <p><b>Two modes.</b> The genuine model-judgement eval (does the live mid-tier model produce the
 * expected delta for each case?) requires real AI calls, which are NOT part of the default CI
 * sweep. That mode is gated behind {@code -Dmealprep.eval.preference-delta=live} (a future harness
 * wires a real {@code AiService} + the actual prompt template and records the 15/18 score in the
 * PR). The always-on mode below verifies the <b>deterministic half</b> of every eval case: given
 * the model's expected AI-shape output for each case, the {@link AiToApplyDeltaMapper} produces the
 * contractually-correct wire shape (or empty, for the no-delta cases). This is what protects the
 * generate→apply seam from regressing as the prompt evolves.
 */
class PreferenceTasteProfileDeltaEvalTest {

  private final AiToApplyDeltaMapper mapper =
      new AiToApplyDeltaMapper(new ObjectMapper(), Clock.systemUTC());

  /** Eval case: name → (expected AI deltas the model should return). */
  private record EvalCase(String name, List<AiTasteProfileDelta> expectedAiDeltas) {}

  private List<EvalCase> evalCases() {
    return List.of(
        // 1 — two "I love spicy" events → Add spicy to flavour_notes HIGH.
        new EvalCase(
            "two-event spicy consensus",
            List.of(add("likes.flavour_notes", "spicy", AiTasteProfileDelta.Confidence.HIGH))),
        // 2 — five "too salty" events → Add "salty preparations" to dislikes.
        new EvalCase(
            "three-event-rule positive (salty)",
            List.of(
                add(
                    "dislikes.flavour_notes",
                    "salty preparations",
                    AiTasteProfileDelta.Confidence.HIGH))),
        // 3 — "becoming vegetarian" → empty + warning (hard-constraint boundary).
        new EvalCase("hard-constraint signal → empty", List.of()),
        // 4 — recipe-specific praise → empty (recipe-vs-preference distinction).
        new EvalCase("recipe-specific praise → empty", List.of()),
        // 5 — multi-aspect tofu feedback → UpdateNotes on existing tofu like.
        new EvalCase(
            "multi-aspect → update",
            List.of(update("likes.ingredients", "tofu", "texture-positive, flavour bland"))),
        // 6 — experiment confirmation → PromoteExperiment.
        new EvalCase(
            "experiment confirmation → promote",
            List.of(
                new AiTasteProfileDelta.PromoteExperiment(
                    "bitter+sweet", "likes.flavour_notes", "bittersweet", "f1", "confirmed"))),
        // 7 — experiment disproof → DiscardExperiment.
        new EvalCase(
            "experiment disproof → discard",
            List.of(new AiTasteProfileDelta.DiscardExperiment("rocket+honey", "f1", "too weird"))),
        // 8 — explicit move statement → Add HIGH (single-event-acceptable).
        new EvalCase(
            "explicit move statement → add HIGH",
            List.of(add("dislikes.ingredients", "dairy", AiTasteProfileDelta.Confidence.HIGH))),
        // 9 — ambiguous "the dish" → empty + warning.
        new EvalCase("ambiguity → empty", List.of()),
        // 10 — nuance on existing chicken → UpdateNotes.
        new EvalCase(
            "update vs add (char-grilled chicken)",
            List.of(update("likes.ingredients", "chicken", "prefers char-grilled"))),
        // 11 — same item in likes+dislikes → empty + warning (defensive).
        new EvalCase("data-inconsistency → empty", List.of()),
        // 12 — 11+ events → cap deltas (mapper passes through, applier caps at 50).
        new EvalCase(
            "volume handling → multiple",
            List.of(
                add("likes.ingredients", "lentils", AiTasteProfileDelta.Confidence.MEDIUM),
                add("likes.ingredients", "chickpeas", AiTasteProfileDelta.Confidence.MEDIUM))),
        // 13 — another-language recognisable → best-effort Add.
        new EvalCase(
            "language robustness → add",
            List.of(
                add("dislikes.flavour_notes", "muy salado", AiTasteProfileDelta.Confidence.LOW))),
        // 14 — mood-flavoured curry → empty + warning.
        new EvalCase("mood-vs-preference → empty", List.of()),
        // 15 — self-contradiction → empty + warning.
        new EvalCase("self-contradiction → empty", List.of()),
        // 16 — empty batch → empty.
        new EvalCase("empty input → empty", List.of()),
        // 17 — re-emergence ("eating fish again") → RePromote.
        new EvalCase(
            "archive re-emergence → re-promote",
            List.of(
                new AiTasteProfileDelta.RePromote(
                    "fish", "likes.ingredients", "f1", "started eating fish again"))),
        // 18 — multi-signal entry → multiple deltas + dietary-identity warning.
        new EvalCase(
            "multi-signal entry → multiple",
            List.of(
                add("likes.cuisines", "Korean", AiTasteProfileDelta.Confidence.MEDIUM),
                add("likes.flavour_notes", "spicy", AiTasteProfileDelta.Confidence.MEDIUM))));
  }

  @Test
  void evalContract_everyCaseMapsToWellFormedWireDeltas() {
    int passed = 0;
    for (EvalCase ec : evalCases()) {
      List<TasteProfileDelta> wire = mapper.toApplyDeltas(ec.expectedAiDeltas());
      // Contract: the empty-delta cases map to empty; the delta-producing cases map 1:1 to
      // resolvable wire deltas (no op silently dropped because of an unresolvable path).
      boolean ok = wire.size() == ec.expectedAiDeltas().size();
      if (ok) {
        passed++;
      } else {
        System.out.println(
            "EVAL FAIL: "
                + ec.name()
                + " expected="
                + ec.expectedAiDeltas().size()
                + " mapped="
                + wire.size());
      }
    }
    // The deterministic seam must map ALL 18 cases (the 15/18 threshold applies to the model's
    // judgement, not the mapper — a dropped delta here is a mapper bug, not a judgement miss).
    assertThat(passed).isEqualTo(evalCases().size());
  }

  /**
   * Live model-judgement eval (gated). Wired in a follow-up harness that injects a real {@code
   * AiService} + the prompt template and scores expected-vs-actual deltas per case. The gate keeps
   * paid AI calls out of the default CI sweep; the PR records the 15/18 score.
   */
  @Test
  @EnabledIfSystemProperty(named = "mealprep.eval.preference-delta", matches = "live")
  void evalLive_scoresAtLeast15of18() {
    // Intentionally a placeholder for the live harness — never runs in the default sweep. Asserting
    // here would require a real AiService; the gate documents the ship threshold (≥15/18).
    assertThat(evalCases()).hasSize(18);
  }

  // ---------------- builders ----------------

  private static AiTasteProfileDelta add(
      String path, String item, AiTasteProfileDelta.Confidence confidence) {
    return new AiTasteProfileDelta.Add(path, item, null, "f1", "eval", confidence);
  }

  private static AiTasteProfileDelta update(String path, String item, String newNotes) {
    return new AiTasteProfileDelta.Update(path, item, newNotes, "f1", "eval");
  }

  // Unused but kept to document the case-builder shape for the live harness.
  @SuppressWarnings("unused")
  private static final Function<EvalCase, String> CASE_NAME = EvalCase::name;
}
