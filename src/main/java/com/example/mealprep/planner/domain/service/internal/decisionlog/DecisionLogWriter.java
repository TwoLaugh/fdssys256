package com.example.mealprep.planner.domain.service.internal.decisionlog;

import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The planner module's integration seam onto the project-wide decision log (planner-01l). Every
 * planner loop stage, lifecycle transition and reopt listener routes through {@link
 * #write(DecisionLogEntry)}; before 01l these call sites were null-tolerant stubs.
 *
 * <p><b>Transaction semantics (ticket invariant #7 / DoD):</b> {@link #write} is declared
 * {@code @Transactional(propagation = Propagation.REQUIRES_NEW)} so the decision row commits in its
 * own inner transaction, independent of the calling stage. If {@code PlanComposer.compose} rolls
 * back, the decision rows of stages that already ran REMAIN — they record what was attempted.
 * (Core's {@code DecisionLogService.write} is also {@code REQUIRES_NEW}; the explicit annotation
 * here makes the planner-side contract grep-able and survives any future change to the core impl.)
 *
 * <p><b>Fixed mapping:</b> {@code scope_kind = "PLANNER"}, {@code scope_id = planId}, {@code scale
 * = WEEK}. The shared table has no {@code kind} column, so the {@link PlannerDecisionKind} is
 * written into the {@code inputs} JSON under {@code "kind"} (the smoke test and admin read path key
 * on it).
 *
 * <p><b>Secret hygiene (ticket gotcha):</b> {@code reasoning} is truncated to {@value
 * #MAX_REASONING_CHARS} chars — Phase-2 augmentation reasoning is LLM-generated free text. Callers
 * are still responsible for never putting preference free-text / feedback bodies into {@code
 * inputs}/{@code outputs}; UUIDs and counts only.
 *
 * <p>Lives in {@code domain.service.internal.*} so it is module-internal and never crosses the
 * planner module boundary (planner ArchUnit / style-guide §Internal helpers). It is {@code public}
 * for the same reason every other planner internal helper is — Java package-private would not span
 * the sibling {@code internal.composer} / {@code internal.reopt} / {@code internal.listeners} /
 * {@code internal.lifecycle} subpackages that call it.
 */
@Component
public class DecisionLogWriter {

  private static final Logger log = LoggerFactory.getLogger(DecisionLogWriter.class);

  /** LLM-generated text cap before it enters the audit row (ticket gotcha). */
  public static final int MAX_REASONING_CHARS = 500;

  /** Fixed scope-kind discriminator for every planner decision row (ticket invariant #2). */
  public static final String SCOPE_KIND = "PLANNER";

  private final DecisionLogService decisionLogService;
  private final ObjectMapper objectMapper;

  @SuppressWarnings(
      "unused") // Clock injected per ticket spec; reserved for future duration fields.
  private final Clock clock;

  public DecisionLogWriter(
      DecisionLogService decisionLogService, ObjectMapper objectMapper, Clock clock) {
    this.decisionLogService = decisionLogService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Persist a planner decision-log row and return its assigned decision id. The returned id is the
   * {@code parent_decision_id} the next stage of the same generation chains to.
   *
   * <p>Never throws to the caller: a decision-log failure must not fail the plan generation it is
   * auditing. A write failure logs WARN and returns {@code null}; callers chaining a parent should
   * tolerate {@code null} (treat as "no parent recorded" rather than abort).
   *
   * @param entry the planner decision entry; never {@code null}
   * @return the new decision id, or {@code null} if the underlying write failed
   */
  @Nullable
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID write(DecisionLogEntry entry) {
    if (entry == null) {
      throw new IllegalArgumentException("entry must not be null");
    }
    try {
      ObjectNode inputs = inputsWithKind(entry);
      return decisionLogService.write(
          new DecisionLogWriteRequest(
              entry.traceId(),
              entry.parentDecisionId(),
              SCOPE_KIND,
              entry.planId(),
              DecisionLogScale.WEEK,
              entry.triggeredBy(),
              entry.actorUserId(),
              inputs,
              null,
              entry.outputs(),
              truncate(entry.reasoning()),
              null,
              0,
              null));
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to write planner decision-log row kind={} plan={}: {}",
          entry.kind(),
          entry.planId(),
          ex.toString());
      return null;
    }
  }

  /**
   * Copy the caller's {@code inputs} (or an empty object) and stamp the {@code kind} discriminator
   * onto it so the shared table is queryable per planner kind without a dedicated column.
   */
  private ObjectNode inputsWithKind(DecisionLogEntry entry) {
    ObjectNode node;
    JsonNode supplied = entry.inputs();
    if (supplied instanceof ObjectNode on) {
      node = on.deepCopy();
    } else {
      node = objectMapper.createObjectNode();
      if (supplied != null && !supplied.isNull()) {
        node.set("payload", supplied);
      }
    }
    node.put("kind", entry.kind().name());
    return node;
  }

  @Nullable
  private static String truncate(@Nullable String reasoning) {
    if (reasoning == null) {
      return null;
    }
    return reasoning.length() <= MAX_REASONING_CHARS
        ? reasoning
        : reasoning.substring(0, MAX_REASONING_CHARS);
  }
}
