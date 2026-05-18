package com.example.mealprep.planner.domain.service.internal.decisionlog;

/**
 * The set of audit-worthy decision points the planner module records in the project-wide {@code
 * decision_log} (planner-01l, ticket invariant #3). One value per loop stage / lifecycle event.
 *
 * <p>The shared {@code decision_log} table has no dedicated {@code kind} column — every planner row
 * carries {@code scope_kind = "PLANNER"}. The kind is therefore persisted inside the row's {@code
 * inputs} JSON under the {@code "kind"} key (see {@link DecisionLogWriter}); the admin read path
 * and the smoke-test DAG reconstruction key off that field.
 */
public enum PlannerDecisionKind {

  /** Entry to {@code PlanComposer.compose} — the root of a generation trace. */
  PLAN_GENERATION_START,

  /** After {@code BeamSearchEngine.search} returns top-N. */
  STAGE_A_DONE,

  /** After {@code RollupBuilder.build} rolls up every candidate. */
  STAGE_B_DONE,

  /** After {@code StageCInvoker.pickOne} returns the chosen index. */
  STAGE_C_DONE,

  /** After {@code Phase2Augmenter.augment} returns augmentations + directives. */
  PHASE_2_DONE,

  /** Once per directive routed to {@code AdaptationService.runPlanTimeRefineJob}. */
  STAGE_D_OUTCOME,

  /** Composer exit. */
  PLAN_GENERATION_COMPLETE,

  /** Every plan state-machine transition (GENERATED&rarr;ACCEPTED, etc.). */
  PLAN_LIFECYCLE_TRANSITION,

  /** Entry to {@code MidWeekReoptCoordinator.requestReopt}. */
  MID_WEEK_REOPT_REQUEST,

  /** Exit of a mid-week re-opt (suggestion id, or skipped-with-reason). */
  MID_WEEK_REOPT_RESULT,

  /** A user accepted a re-opt suggestion via the 01j endpoint. */
  REOPT_SUGGESTION_ACCEPTED,

  /** A user rejected a re-opt suggestion. */
  REOPT_SUGGESTION_REJECTED,

  /** Each materiality-positive listener invocation (links an external event to a reopt request). */
  LISTENER_TRIGGER
}
