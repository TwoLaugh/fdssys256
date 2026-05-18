package com.example.mealprep.planner.domain.service.internal.listeners;

import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.event.AdaptationJobCompletedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobFailedEvent;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Handles the adaptation-pipeline's job-outcome callbacks for adaptations the planner kicked off as
 * Stage-D refine-directives (planner-01k §7 / §8). Package-private {@code @Component}.
 *
 * <p>The {@code @ConditionalOnClass} keeps this bean unregistered if the adaptation-pipeline event
 * types are ever absent from the classpath (a future module split) — per the ticket's soft-dep
 * gotcha. The string form is required: a class-literal {@code @ConditionalOnClass} would force the
 * type to load before the condition is evaluated, defeating the guard. Adaptation-pipeline-01b/01f
 * ARE merged today so the bean is active.
 *
 * <p><strong>Round-7 retro rule:</strong> both listeners are {@code @Transactional(propagation =
 * REQUIRES_NEW)} on top of {@code @TransactionalEventListener(AFTER_COMMIT)} (plain {@code
 * REQUIRED}/{@code SUPPORTS} fail context-load on a transactional event listener).
 *
 * <p><strong>Common case is a no-op audit.</strong> The planner composer awaits Stage-D adaptation
 * synchronously (planner-01h), so by the time this AFTER_COMMIT callback fires the originating plan
 * is already persisted with the adapted recipe — this handler only writes a decision-log row
 * linking the planner's parent decision to the adaptation's child decision (the trace chain).
 * Mutating a still-{@code GENERATING} plan from here (the rare race the ticket calls out) would
 * require a native {@code @Modifying} UPDATE to dodge a {@code StaleObjectStateException} against
 * Hibernate's lingering dirty-check (round-8 retro) — but planner-01a..01i never expose a {@code
 * GENERATING} status (the enum is {@code DRAFT/GENERATED/ACTIVE/…}); a persisted plan is always at
 * least {@code GENERATED}. So there is no in-flight plan to mutate and this handler is, by
 * construction, audit-only until a later ticket introduces an async-compose path.
 *
 * <p>Decision-log writes are null-tolerant via {@link ObjectProvider} — the {@code
 * DecisionLogService} is present today but routed defensively so a future core.audit extraction
 * can't brick this AFTER_COMMIT path (mirrors {@code MidWeekReoptCoordinator}).
 *
 * <p>Listener bodies never re-throw — AFTER_COMMIT, the upstream tx already committed.
 */
@Component
@ConditionalOnClass(name = "com.example.mealprep.adaptation.event.AdaptationJobCompletedEvent")
class AdaptationCallbackHandler {

  private static final Logger log = LoggerFactory.getLogger(AdaptationCallbackHandler.class);

  private final ObjectProvider<DecisionLogService> decisionLogProvider;
  private final ObjectMapper objectMapper;

  AdaptationCallbackHandler(
      ObjectProvider<DecisionLogService> decisionLogProvider, ObjectMapper objectMapper) {
    this.decisionLogProvider = decisionLogProvider;
    this.objectMapper = objectMapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onAdaptationJobCompleted(AdaptationJobCompletedEvent event) {
    try {
      log.info(
          "onAdaptationJobCompleted jobId={} recipeId={} outcomeKind={} outcomeTargetId={}"
              + " classification={} — composer awaits Stage-D synchronously; audit-only",
          event.jobId(),
          event.recipeId(),
          event.outcomeKind(),
          event.outcomeTargetId(),
          event.classification());
      ObjectNode inputs = objectMapper.createObjectNode();
      inputs.put("jobId", String.valueOf(event.jobId()));
      inputs.put("recipeId", String.valueOf(event.recipeId()));
      inputs.put("outcomeKind", event.outcomeKind() == null ? null : event.outcomeKind().name());
      ObjectNode outputs = objectMapper.createObjectNode();
      outputs.put(
          "outcomeTargetId",
          event.outcomeTargetId() == null ? null : event.outcomeTargetId().toString());
      outputs.put(
          "classification", event.classification() == null ? null : event.classification().name());
      writeDecisionLog(
          "stage_d_outcome",
          event.traceId(),
          event.recipeId(),
          inputs,
          outputs,
          "Stage-D adaptation completed; planner parent decision linked to adaptation child");
    } catch (RuntimeException ex) {
      log.warn(
          "onAdaptationJobCompleted failed for jobId={}: {}", event.jobId(), ex.toString(), ex);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onAdaptationJobFailed(AdaptationJobFailedEvent event) {
    try {
      if (event.reason() == JobFailureReason.AI_UNAVAILABLE) {
        // Composer should already have caught this and surfaced a quality warning on the plan;
        // this is a defensive INFO (AI-unavailable is expected graceful-degrade, not an error).
        log.info(
            "onAdaptationJobFailed jobId={} recipeId={} reason=AI_UNAVAILABLE — Stage-D adaptation"
                + " unavailable; composer should have set qualityWarning. excerpt={}",
            event.jobId(),
            event.recipeId(),
            event.excerpt());
      } else {
        log.info(
            "onAdaptationJobFailed jobId={} recipeId={} reason={} excerpt={}",
            event.jobId(),
            event.recipeId(),
            event.reason(),
            event.excerpt());
      }
      ObjectNode inputs = objectMapper.createObjectNode();
      inputs.put("jobId", String.valueOf(event.jobId()));
      inputs.put("recipeId", String.valueOf(event.recipeId()));
      inputs.put("reason", event.reason() == null ? null : event.reason().name());
      ObjectNode outputs = objectMapper.createObjectNode();
      outputs.put("excerpt", event.excerpt());
      writeDecisionLog(
          "stage_d_failure",
          event.traceId(),
          event.recipeId(),
          inputs,
          outputs,
          "Stage-D adaptation failed (" + event.reason() + ")");
    } catch (RuntimeException ex) {
      log.warn("onAdaptationJobFailed failed for jobId={}: {}", event.jobId(), ex.toString(), ex);
    }
  }

  private void writeDecisionLog(
      String kind,
      java.util.UUID traceId,
      java.util.UUID scopeId,
      ObjectNode inputs,
      ObjectNode outputs,
      String reasoning) {
    DecisionLogService writer = decisionLogProvider.getIfAvailable();
    if (writer == null) {
      log.warn(
          "DecisionLogService unavailable; skipping {} decision-log row for trace={}",
          kind,
          traceId);
      return;
    }
    try {
      writer.write(
          new DecisionLogWriteRequest(
              traceId,
              null, // parent decision chain resolved by the audit module via traceId
              kind,
              scopeId,
              DecisionLogScale.RECIPE,
              "system",
              null,
              inputs,
              null,
              outputs,
              reasoning,
              null,
              1,
              null));
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to write {} decision-log row for trace={}: {}", kind, traceId, ex.toString());
    }
  }
}
