/**
 * In-memory mock store — the foundation of the playable mocked app.
 *
 * Tiny external-store pattern: an immutable state object replaced on every
 * mutation, a pub/sub listener set, and a `useStore` hook built on
 * `useSyncExternalStore`. Selectors must return stored references or
 * primitives (the state object is replaced wholesale, so slice references
 * stay stable between mutations).
 */

import { useSyncExternalStore } from "react";
import { MOCK_USER_ID, WEEK_DATES } from "./nutritionSeed";
import {
  CURRENT_WEEK_START,
  RECIPE_NAME_FALLBACK,
  addDaysIso,
  buildPlan,
} from "./plannerSeed";
import {
  computeDiff,
  hashCode,
  ingredientsFromRequest,
  mainBranchId,
  ratingAggregate,
  stepsFromRequest,
} from "./recipeLogic";
import {
  buildRecipe,
  DEDUP_DEMO_URL,
  DEDUP_PARSED_RECIPE,
  DISCOVERY_RUN_SCRIPT,
  GENERIC_PARSE_WARNINGS,
  GENERIC_PARSED_RECIPE,
  HARD_CONSTRAINT_KEYS,
  rowFromScript,
  SELF_ACTOR,
} from "./recipeSeed";
import { createSeed, MOCK_TODAY_ISO } from "./seed";
import type {
  ActivityLevel,
  AppNotification,
  ConfidenceTier,
  ConstraintKind,
  CreateBranchRequest,
  CreateRatingRequest,
  CreateRecipeRequest,
  CreateSubstitutionRequest,
  DailyAggregateDto,
  DayDto,
  DirectiveUserModification,
  DiscoveryJobDto,
  EnforcementDirection,
  FeedbackEntry,
  FeedbackRoute,
  IngredientNutritionDocument,
  IngredientNutritionDto,
  IntakeDayDto,
  IntakeEntryDto,
  IntakeSlotDto,
  IntakeSnackDto,
  LogSnackRequest,
  MealSlot,
  MealSlotDto,
  MealSlotKey,
  NotificationKind,
  NutritionState,
  PendingChangeDto,
  PinnedReason,
  PlanDto,
  ProposedReoptAssignmentsDocument,
  RecipeDiffDto,
  RecipeDto,
  RecipeImportPreview,
  RecipeNutritionResultDto,
  RecipeRatingDto,
  RecipeSubstitutionDto,
  RecipeVersionDto,
  ReoptSuggestionDto,
  RevertToVersionRequest,
  SlotState,
  StartDiscoveryJobRequest,
  StoreState,
  TargetsDto,
  ToastItem,
  UpdateRecipeManualEditRequest,
  UpdateTargetsRequest,
  WeeklyAggregateDto,
} from "./types";

/* ---- core ------------------------------------------------------------------ */

let state: StoreState = createSeed();
const listeners = new Set<() => void>();

function getSnapshot(): StoreState {
  return state;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function mutate(producer: (s: StoreState) => StoreState): void {
  state = producer(state);
  listeners.forEach((l) => l());
}

/** Read a slice of the store; re-renders when the selected value changes. */
export function useStore<T>(selector: (s: StoreState) => T): T {
  return useSyncExternalStore(subscribe, () => selector(getSnapshot()));
}

let notificationSeq = 100;

function pushNotification(
  s: StoreState,
  kind: NotificationKind,
  title: string,
): StoreState {
  const item: AppNotification = {
    id: `n${++notificationSeq}`,
    kind,
    title,
    time: "Just now",
    read: false,
  };
  return { ...s, notifications: [item, ...s.notifications] };
}

/* ---- toasts ------------------------------------------------------------------ */

let toastSeq = 0;

/** Transient toast (409-guard messages, replay notices, confirmations). */
export function pushToast(text: string, tone: ToastItem["tone"] = "info"): void {
  const id = ++toastSeq;
  mutate((s) => ({ ...s, toasts: [...s.toasts, { id, text, tone }] }));
  setTimeout(() => {
    mutate((s) =>
      s.toasts.some((t) => t.id === id)
        ? { ...s, toasts: s.toasts.filter((t) => t.id !== id) }
        : s,
    );
  }, 5200);
}

/* ---- planner: shared helpers ----------------------------------------------------
 * Contract shapes throughout (design/frontend/pages/plan.md). Slots carry
 * recipe ids only — recipeName stands in for the client-side recipe cache
 * join (spec s1).
 */

export function recipeName(recipes: RecipeDto[], recipeId: string): string {
  return (
    recipes.find((r) => r.id === recipeId)?.name ??
    RECIPE_NAME_FALLBACK[recipeId] ??
    recipeId
  );
}

export function findPlan(s: StoreState, planId: string): PlanDto | undefined {
  return s.planner.plans.find((p) => p.id === planId);
}

/** Generations of one week, newest first (history drawer #3). */
export function plansForWeek(s: StoreState, weekStartDate: string): PlanDto[] {
  return s.planner.plans
    .filter((p) => p.weekStartDate === weekStartDate)
    .sort((a, b) => b.generation - a.generation);
}

/** GET /plans/active equivalent — undefined plays the 404 empty state. */
export function activePlanForWeek(
  s: StoreState,
  weekStartDate: string,
): PlanDto | undefined {
  return s.planner.plans.find(
    (p) => p.weekStartDate === weekStartDate && p.status === "ACTIVE",
  );
}

function findSlot(
  plan: PlanDto,
  slotId: string,
): { day: DayDto; slot: MealSlotDto } | undefined {
  for (const day of plan.days) {
    const slot = day.slots.find((sl) => sl.id === slotId);
    if (slot) return { day, slot };
  }
  return undefined;
}

function replacePlan(s: StoreState, next: PlanDto): StoreState {
  return {
    ...s,
    planner: {
      ...s.planner,
      plans: s.planner.plans.map((p) => (p.id === next.id ? next : p)),
    },
  };
}

function withSlot(
  plan: PlanDto,
  slotId: string,
  fn: (sl: MealSlotDto) => MealSlotDto,
): PlanDto {
  return {
    ...plan,
    days: plan.days.map((d) =>
      d.slots.some((sl) => sl.id === slotId)
        ? { ...d, slots: d.slots.map((sl) => (sl.id === slotId ? fn(sl) : sl)) }
        : d,
    ),
    version: plan.version + 1, // backend force-bumps on slot writes
    updatedAt: nowStamp(),
  };
}

/** Mock clock for planner writes (the fixed demo "today", evening). */
function nowStamp(): string {
  return `${MOCK_TODAY_ISO}T18:05:00Z`;
}

/* ---- planner: slot state machine (#11) ----------------------------------------------
 * PLANNED → COOKING → COOKED → EATEN | SKIPPED, never backwards. Illegal
 * requests 409 (toast + "re-fetch"); the seeded raced slot plays the
 * "advanced on another device" row of the status-code map (spec §8).
 */

const SLOT_TRANSITIONS: Record<SlotState, SlotState[]> = {
  PLANNED: ["COOKING", "SKIPPED"],
  COOKING: ["COOKED", "SKIPPED"],
  COOKED: ["EATEN"],
  EATEN: [],
  SKIPPED: [],
};

const PIN_BY_STATE: Partial<Record<SlotState, PinnedReason>> = {
  COOKING: "COOKING",
  COOKED: "COOKED",
  EATEN: "EATEN",
  SKIPPED: "SKIPPED",
};

/**
 * PATCH /plans/{planId}/slots/{slotId}/state. Returns true when the planner
 * write landed — callers pairing a nutrition dual-write (Today page,
 * today.md §3b) only fire the intake call on success.
 */
export function changeSlotState(
  planId: string,
  slotId: string,
  newState: SlotState,
): boolean {
  const s = state;
  const plan = findPlan(s, planId);
  if (!plan) {
    pushToast("Plan no longer exists — re-fetching", "warn");
    return false;
  }
  if (plan.status !== "ACTIVE") {
    pushToast("409 — slot actions are only available on the active plan", "warn");
    return false;
  }

  // Raced-device demo: the mock "server" already advanced this slot; the
  // first action 409s with the server detail and the grid re-fetches.
  const raced = s.planner.racedSlot;
  if (raced && raced.slotId === slotId) {
    mutate((st) => {
      const p = findPlan(st, planId);
      if (!p) return st;
      const refreshed = withSlot(p, slotId, (sl) => ({
        ...sl,
        state: raced.serverState,
        pinnedReason: PIN_BY_STATE[raced.serverState] ?? sl.pinnedReason,
      }));
      return {
        ...replacePlan(st, refreshed),
        planner: { ...replacePlan(st, refreshed).planner, racedSlot: null },
      };
    });
    pushToast(
      `409 — slot was already ${raced.serverState.toLowerCase()} on another device; plan re-fetched`,
      "warn",
    );
    return false;
  }

  const found = findSlot(plan, slotId);
  if (!found) {
    pushToast("Slot no longer exists — re-fetching", "warn");
    return false;
  }
  if (!SLOT_TRANSITIONS[found.slot.state].includes(newState)) {
    pushToast(
      `409 — invalid slot transition ${found.slot.state} → ${newState} (slots never move backwards)`,
      "warn",
    );
    return false;
  }

  mutate((st) => {
    const p = findPlan(st, planId);
    if (!p) return st;
    return replacePlan(
      st,
      withSlot(p, slotId, (sl) => ({
        ...sl,
        state: newState,
        pinnedReason: PIN_BY_STATE[newState] ?? null,
      })),
    );
  });
  return true;
}

/* ---- planner: re-opt suggestions (#12, #13, #14) -------------------------------------
 * Accepting writes a NEW GENERATED generation and supersedes the current
 * plan — the user then accepts *that plan* (two-step confirm, spec §3e).
 * The diff (proposedAssignments) is only revealed by the accept response
 * (contract gap, spec §8 Q2).
 */

let planSeq = 100;
let suggestionSeq = 10;

function applyProposal(
  plan: PlanDto,
  proposal: ProposedReoptAssignmentsDocument,
  planKey: string,
): DayDto[] {
  const bySlot = new Map(proposal.changes.map((c) => [c.slotId, c]));
  return plan.days.map((d) => ({
    ...d,
    id: `${planKey}-${d.date}`,
    slots: d.slots.map((sl) => {
      const change = bySlot.get(sl.id);
      if (!change) return sl;
      return {
        ...sl,
        state: "PLANNED" as const,
        pinnedReason: null,
        scheduledRecipe: {
          id: `sr-${planKey}-${sl.id}`,
          recipeId: change.newRecipeId,
          recipeVersionId: change.newRecipeVersionId ?? `${change.newRecipeId}-v1`,
          recipeBranchId: change.newRecipeBranchId ?? `${change.newRecipeId}-main`,
          servings: change.newServings,
          batchCookSessionId: null,
          augmentationNotes: null,
          augmentationSource: null,
          phase2Addition: false,
        },
      };
    }),
  }));
}

export function acceptSuggestion(suggestionId: string): void {
  mutate((s) => {
    const suggestion = s.planner.suggestions.find((x) => x.id === suggestionId);
    const proposal = s.planner.proposedBySuggestion[suggestionId];
    if (!suggestion || suggestion.status !== "PENDING" || !proposal) return s;
    const source = findPlan(s, suggestion.planId);
    if (!source) return s;

    const planKey = `g${++planSeq}`;
    const newPlan: PlanDto = {
      ...source,
      id: `plan-${planKey}`,
      generation: source.generation + 1,
      replacesPlanId: source.id,
      status: "GENERATED",
      triggerKind: "MID_WEEK_REOPT",
      triggerEventId: suggestion.triggerEventId ?? null,
      traceId: `trace-${planKey}`,
      decisionId: `decision-${planKey}`,
      acceptedAt: null,
      completedAt: null,
      rejectedAt: null,
      rejectedReason: null,
      abandonedAt: null,
      abandonedReason: null,
      days: applyProposal(source, proposal, planKey),
      rollupSummary: {
        ...source.rollupSummary,
        weekly: {
          ...source.rollupSummary.weekly,
          costEstimateGbp:
            Math.round((source.rollupSummary.weekly.costEstimateGbp - 1.1) * 100) /
            100,
          varietyIndex:
            Math.round((source.rollupSummary.weekly.varietyIndex + 0.02) * 100) /
            100,
        },
      },
      version: 1,
      createdAt: nowStamp(),
      updatedAt: nowStamp(),
    };

    const out: StoreState = {
      ...s,
      planner: {
        ...s.planner,
        // Accept supersedes the current plan immediately (spec §3e): the
        // active read 404s until the new generation is accepted.
        plans: [
          newPlan,
          ...s.planner.plans.map((p) =>
            p.id === source.id && p.status === "ACTIVE"
              ? { ...p, status: "SUPERSEDED" as const, updatedAt: nowStamp() }
              : p,
          ),
        ],
        suggestions: s.planner.suggestions.filter((x) => x.id !== suggestionId),
        lastReoptOutcome: {
          newPlanId: newPlan.id,
          dto: {
            id: suggestion.id,
            planId: suggestion.planId,
            triggerKind: suggestion.triggerKind,
            triggerEventId: suggestion.triggerEventId ?? null,
            traceId: `trace-${suggestion.id}`,
            decisionId: null,
            summary: suggestion.summary,
            status: "ACCEPTED",
            proposedAssignments: proposal,
            createdAt: suggestion.createdAt,
            expiresAt: suggestion.expiresAt ?? null,
          },
        },
      },
    };
    pushToast("Changes applied as a new draft plan — review and accept");
    return pushNotification(
      out,
      "plan",
      `Re-optimisation applied — generation ${newPlan.generation} awaiting your approval`,
    );
  });
}

/** Dismiss (#14) — suggestion → REJECTED, strikes clear (they are derived). */
export function rejectSuggestion(suggestionId: string): void {
  mutate((s) => {
    if (!s.planner.suggestions.some((x) => x.id === suggestionId)) return s;
    return {
      ...s,
      planner: {
        ...s.planner,
        suggestions: s.planner.suggestions.filter((x) => x.id !== suggestionId),
      },
    };
  });
}

/* ---- planner: plan lifecycle (#7 accept · #8 reject · #9 abandon · #10 revert) ------- */

export function acceptPlan(planId: string): void {
  const plan = findPlan(state, planId);
  if (!plan) return;
  if (plan.status !== "GENERATED") {
    pushToast("409 — this plan changed state elsewhere; re-fetched", "warn");
    return;
  }
  mutate((s) => {
    const p = findPlan(s, planId);
    if (!p || p.status !== "GENERATED") return s;
    const out: StoreState = {
      ...s,
      planner: {
        ...s.planner,
        plans: s.planner.plans.map((x) => {
          if (x.id === planId) {
            return {
              ...x,
              status: "ACTIVE" as const,
              acceptedAt: nowStamp(),
              updatedAt: nowStamp(),
            };
          }
          if (x.weekStartDate === p.weekStartDate && x.status === "ACTIVE") {
            return { ...x, status: "SUPERSEDED" as const, updatedAt: nowStamp() };
          }
          return x;
        }),
        lastReoptOutcome:
          s.planner.lastReoptOutcome?.newPlanId === planId
            ? null
            : s.planner.lastReoptOutcome,
        generation:
          s.planner.generation.resultPlanId === planId
            ? { ...s.planner.generation, status: "idle", resultPlanId: null }
            : s.planner.generation,
      },
    };
    return pushNotification(
      out,
      "plan",
      `Plan accepted — generation ${p.generation} is now active for the week of ${p.weekStartDate}`,
    );
  });
}

/** Idempotent: re-rejecting an already-REJECTED plan is a 200 no-op. */
export function rejectPlan(planId: string, reason?: string): void {
  const plan = findPlan(state, planId);
  if (!plan || plan.status === "REJECTED") return;
  if (plan.status !== "GENERATED") {
    pushToast("409 — this plan changed state elsewhere; re-fetched", "warn");
    return;
  }
  mutate((s) => {
    const p = findPlan(s, planId);
    if (!p || p.status !== "GENERATED") return s;
    const out = replacePlan(s, {
      ...p,
      status: "REJECTED",
      rejectedAt: nowStamp(),
      rejectedReason: reason?.trim() ? reason.trim().slice(0, 255) : null,
    });
    return {
      ...out,
      planner: {
        ...out.planner,
        lastReoptOutcome:
          out.planner.lastReoptOutcome?.newPlanId === planId
            ? null
            : out.planner.lastReoptOutcome,
        generation:
          out.planner.generation.resultPlanId === planId
            ? { ...out.planner.generation, status: "idle", resultPlanId: null }
            : out.planner.generation,
      },
    };
  });
}

export function abandonPlan(planId: string, reason?: string): void {
  const plan = findPlan(state, planId);
  if (!plan) return;
  if (plan.status !== "ACTIVE") {
    pushToast("409 — only the active plan can be abandoned", "warn");
    return;
  }
  mutate((s) => {
    const p = findPlan(s, planId);
    if (!p || p.status !== "ACTIVE") return s;
    const out = replacePlan(s, {
      ...p,
      status: "ABANDONED",
      abandonedAt: nowStamp(),
      abandonedReason: reason?.trim() ? reason.trim().slice(0, 255) : null,
    });
    return pushNotification(
      out,
      "plan",
      `Week of ${p.weekStartDate} abandoned — generate a new plan when ready`,
    );
  });
}

/**
 * POST /plans/revert — copy-forward semantics: a brand-new GENERATED
 * generation with content copied from the target; recipes that now fail
 * hard constraints are stripped (mock rule: anything using the spoiled
 * chicken breast) and unfillable slots ship empty with qualityWarning.
 */
export function revertToPlan(targetHistoricalPlanId: string): void {
  const STRIPPED = new Set(["chicken-stir-fry", "chicken-wrap"]);
  mutate((s) => {
    const target = findPlan(s, targetHistoricalPlanId);
    if (!target || target.status === "ACTIVE" || target.status === "DRAFT") {
      return s;
    }
    const latestGen = Math.max(
      0,
      ...s.planner.plans
        .filter((p) => p.weekStartDate === target.weekStartDate)
        .map((p) => p.generation),
    );
    const planKey = `g${++planSeq}`;
    let strippedCount = 0;
    const days: DayDto[] = target.days.map((d) => ({
      ...d,
      id: `${planKey}-${d.date}`,
      slots: d.slots.map((sl) => {
        const strip =
          sl.scheduledRecipe != null && STRIPPED.has(sl.scheduledRecipe.recipeId);
        if (strip) strippedCount += 1;
        return {
          ...sl,
          id: `${planKey}-${sl.id}`,
          state: "PLANNED" as const,
          pinnedReason: null,
          scheduledRecipe: strip
            ? null
            : sl.scheduledRecipe && {
                ...sl.scheduledRecipe,
                id: `sr-${planKey}-${sl.id}`,
              },
        };
      }),
    }));
    const reverted = buildPlan({
      id: `plan-${planKey}`,
      generation: latestGen + 1,
      replacesPlanId:
        s.planner.plans.find(
          (p) =>
            p.weekStartDate === target.weekStartDate && p.generation === latestGen,
        )?.id ?? target.id,
      weekStartDate: target.weekStartDate,
      status: "GENERATED",
      triggerKind: "USER_INITIATED",
      qualityWarning: strippedCount > 0,
      createdAt: nowStamp(),
      scoreBreakdown: target.scoreBreakdown,
      rollupSummary:
        strippedCount > 0
          ? {
              ...target.rollupSummary,
              weekly: {
                ...target.rollupSummary.weekly,
                constraintViolations: [
                  `${strippedCount} slot${strippedCount === 1 ? "" : "s"} could not be refilled — chicken breast no longer available`,
                ],
              },
            }
          : target.rollupSummary,
      days,
    });
    const out: StoreState = {
      ...s,
      planner: {
        ...s.planner,
        plans: [reverted, ...s.planner.plans],
        generation: {
          ...s.planner.generation,
          status: "review",
          weekStartDate: target.weekStartDate,
          resultPlanId: reverted.id,
          replayed: false,
        },
      },
    };
    if (strippedCount > 0) {
      pushToast(
        `${strippedCount} slot${strippedCount === 1 ? "" : "s"} could not be refilled — accept the partial plan or re-optimise`,
        "warn",
      );
    }
    return pushNotification(
      out,
      "plan",
      `Reverted to generation ${target.generation} — copied forward as generation ${reverted.generation} (awaiting approval)`,
    );
  });
}

/* ---- planner: generation flow (#5 feasibility · #6 generate) --------------------------
 * One blocking POST returning ONE composed plan — Stage C picks server-side;
 * there is no candidates endpoint (spec §4c / §8 Q1). Idempotency-Key fake:
 * one key per user intent, kept until a 2xx; re-submitting the same intent
 * serves the cached plan back (200 replay); "Regenerate all" mints a new key.
 */

let idemSeq = 0;

/**
 * Enter the stepper for a target week. The Idempotency-Key persists for the
 * same week's intent (re-submitting serves the cached 200 replay); changing
 * week is a new intent. An in-flight generation is never interrupted.
 */
export function openGenerateFlow(weekStartDate: string): void {
  mutate((s) => {
    const g = s.planner.generation;
    if (g.weekStartDate === weekStartDate && g.status === "generating") return s;
    return {
      ...s,
      planner: {
        ...s.planner,
        generation: {
          ...g,
          status: "idle",
          weekStartDate,
          forceRegenerateIfActive: false,
          idempotencyKey:
            g.weekStartDate === weekStartDate ? g.idempotencyKey : null,
          resultPlanId: null,
          replayed: false,
        },
      },
    };
  });
}

export function setForceRegenerate(value: boolean): void {
  mutate((s) => ({
    ...s,
    planner: {
      ...s.planner,
      generation: { ...s.planner.generation, forceRegenerateIfActive: value },
    },
  }));
}

const GENERATED_DINNERS = [
  "miso-salmon-traybake",
  "black-bean-tacos",
  "gnocchi-al-forno",
  "prawn-stir-fry",
  "shakshuka",
  "chickpea-spinach-curry",
  "veggie-chilli",
  "chicken-pilaf",
  "fish-tacos",
];
const GENERATED_BREAKFASTS = [
  "overnight-oats",
  "eggs-on-toast",
  "greek-yoghurt-bowl",
  "shakshuka",
  "pancakes",
];
const GENERATED_LUNCHES = [
  "grain-bowl",
  "soup-bread",
  "leftover-curry",
  "tuna-melt",
  "chicken-wrap",
];

function generatedSlot(
  planKey: string,
  date: string,
  slotIndex: number,
  kind: MealSlotDto["kind"],
  recipeId: string | null,
): MealSlotDto {
  const shared = kind === "DINNER";
  return {
    id: `${planKey}-${date}-${kind.toLowerCase()}-${slotIndex}`,
    slotIndex,
    kind,
    label: kind === "BREAKFAST" ? "Breakfast" : kind === "LUNCH" ? "Lunch" : "Dinner",
    timeBudgetMin: kind === "DINNER" ? 45 : kind === "BREAKFAST" ? 15 : 20,
    shared,
    eaters: shared ? ["m1", "m2", "m3", "m4"] : ["m1"],
    state: "PLANNED",
    pinnedReason: null,
    mealTime: kind === "BREAKFAST" ? "08:00" : kind === "DINNER" ? "19:00" : null,
    prepStepAtTime: null,
    scheduledRecipe:
      recipeId === null
        ? null
        : {
            id: `sr-${planKey}-${date}-${slotIndex}`,
            recipeId,
            recipeVersionId: `${recipeId}-v1`,
            recipeBranchId: `${recipeId}-main`,
            servings: shared ? 4 : 1,
            batchCookSessionId: null,
            augmentationNotes: null,
            augmentationSource: null,
            phase2Addition: false,
          },
  };
}

/**
 * Compose one GENERATED plan for the target week (the mock Stage A→D).
 * Re-optimising the current week preserves pinned slots — eaten / cooking /
 * cooked / skipped / user-pinned meals never regenerate (HLD rule).
 */
function composePlan(s: StoreState, weekStartDate: string, round: number): PlanDto {
  const planKey = `g${++planSeq}`;
  const vary = (i: number, pool: string[]): string =>
    pool[(i + round * 3) % pool.length];
  const infeasible = s.planner.feasibility[weekStartDate]?.feasible === false;
  const source = activePlanForWeek(s, weekStartDate);

  const days: DayDto[] = Array.from({ length: 7 }, (_, i) => {
    const date = addDaysIso(weekStartDate, i);
    const sourceDay = source?.days.find((d) => d.date === date);
    const slots: MealSlotDto[] = sourceDay
      ? sourceDay.slots.map((sl, idx) => {
          if (sl.pinnedReason != null || sl.state !== "PLANNED") {
            // Pinned content copies forward verbatim.
            return { ...sl, id: `${planKey}-${sl.id}` };
          }
          const pool =
            sl.kind === "DINNER"
              ? GENERATED_DINNERS
              : sl.kind === "BREAKFAST"
                ? GENERATED_BREAKFASTS
                : GENERATED_LUNCHES;
          return {
            ...sl,
            id: `${planKey}-${sl.id}`,
            scheduledRecipe: sl.scheduledRecipe && {
              ...sl.scheduledRecipe,
              id: `sr-${planKey}-${sl.id}`,
              recipeId: vary(i + idx, pool),
            },
          };
        })
      : [
          generatedSlot(planKey, date, 0, "BREAKFAST", vary(i, GENERATED_BREAKFASTS)),
          generatedSlot(planKey, date, 1, "LUNCH", vary(i + 1, GENERATED_LUNCHES)),
          generatedSlot(
            planKey,
            date,
            2,
            "DINNER",
            infeasible && i === 3 ? null : vary(i, GENERATED_DINNERS),
          ),
        ];
    return { id: `${planKey}-${date}`, date, notes: null, slots: slots };
  });

  const latestGen = Math.max(
    0,
    ...s.planner.plans
      .filter((p) => p.weekStartDate === weekStartDate)
      .map((p) => p.generation),
  );
  const wobble = (base: number, spread: number): number =>
    Math.round((base + (((round * 7) % (spread * 2 + 1)) - spread) / 100) * 100) /
    100;

  return buildPlan({
    id: `plan-${planKey}`,
    generation: latestGen + 1,
    replacesPlanId:
      s.planner.plans.find(
        (p) => p.weekStartDate === weekStartDate && p.generation === latestGen,
      )?.id ?? null,
    weekStartDate,
    status: "GENERATED",
    triggerKind: "USER_INITIATED",
    qualityWarning: infeasible,
    aiAugmented: !infeasible, // Stage C fallback rides the infeasible demo
    createdAt: nowStamp(),
    scoreBreakdown: {
      preference: wobble(0.92, 3),
      nutrition: infeasible ? 0.71 : wobble(0.89, 3),
      cost: wobble(0.85, 4),
      variety: wobble(0.81, 4),
      time: wobble(0.9, 2),
      batch: 0.82,
      provisions: 0.88,
      composite: infeasible ? wobble(0.79, 3) : wobble(0.9, 3),
      nutritionFloorGatePassed: !infeasible,
      varietyGatePassed: true,
      weightSchemeVersion: "v3",
    },
    rollupSummary: {
      daily: days.map((d, i) => ({
        date: d.date,
        kcal: 2150,
        proteinG: infeasible && (i === 1 || i === 3) ? 110 : 172,
        fatG: 67,
        carbsG: 218,
        fibreG: 27,
        costGbp: 7.4,
        totalTimeMin: i === 6 ? 80 : 30 + ((i + round) % 3) * 5,
        violations:
          infeasible && i === 3
            ? ["Protein 104 g vs 120 g floor", "Dinner slot unfilled"]
            : infeasible && i === 1
              ? ["Protein 112 g vs 120 g floor"]
              : [],
      })),
      weekly: {
        kcalTotal: 15050,
        proteinAvgG: infeasible ? 158 : 174,
        fatAvgG: 67,
        carbsAvgG: 218,
        costEstimateGbp: wobble(53, 0) + ((round * 2) % 5) - 2,
        costConfidence: 0.83,
        staleIngredientCount: 2,
        varietyIndex: wobble(0.81, 4),
        batchCookSessions: source ? 2 : 1,
        constraintViolations: infeasible
          ? [
              "Protein floor 120 g unmet on Tue and Thu within the £55 budget",
              "Thu dinner unfilled — no feasible recipe under current constraints",
            ]
          : [],
      },
    },
    days,
  });
}

/**
 * POST /plans/generate. 409 when a generation is already running; 200
 * cached replay when the intent's Idempotency-Key was already served;
 * otherwise a blocking ~1.6 s compose → 201 + review.
 */
export function requestGeneration(): void {
  const g = state.planner.generation;
  if (g.status === "generating") {
    pushToast("409 — a generation is already running; try again shortly", "warn");
    return;
  }
  const key = g.idempotencyKey ?? `idem-${++idemSeq}-${g.weekStartDate}`;
  const servedPlanId = g.served[key];
  if (servedPlanId && findPlan(state, servedPlanId)) {
    mutate((s) => ({
      ...s,
      planner: {
        ...s.planner,
        generation: {
          ...s.planner.generation,
          idempotencyKey: key,
          status: "review",
          resultPlanId: servedPlanId,
          replayed: true,
        },
      },
    }));
    pushToast("Already generated — showing the existing result (200 replay)");
    return;
  }
  mutate((s) => ({
    ...s,
    planner: {
      ...s.planner,
      generation: { ...s.planner.generation, status: "generating", idempotencyKey: key },
    },
  }));
  setTimeout(() => {
    mutate((s) => {
      const gen = s.planner.generation;
      if (gen.status !== "generating" || gen.idempotencyKey !== key) return s;
      const plan = composePlan(s, gen.weekStartDate, gen.round);
      return {
        ...s,
        planner: {
          ...s.planner,
          plans: [plan, ...s.planner.plans],
          generation: {
            ...gen,
            status: "review",
            resultPlanId: plan.id,
            replayed: false,
            served: { ...gen.served, [key]: plan.id },
          },
        },
      };
    });
  }, 1600);
}

/** "Regenerate all" — a NEW user intent: mints a fresh Idempotency-Key. */
export function regenerateAll(): void {
  if (state.planner.generation.status === "generating") return;
  mutate((s) => ({
    ...s,
    planner: {
      ...s.planner,
      generation: {
        ...s.planner.generation,
        idempotencyKey: null,
        round: s.planner.generation.round + 1,
        status: "idle",
        resultPlanId: null,
        replayed: false,
      },
    },
  }));
  requestGeneration();
}

/* ---- grocery ----------------------------------------------------------------------- */

/** Toggle an item between open and bought (toggling back is the undo). */
export function markBought(groupIdx: number, itemIdx: number): void {
  mutate((s) => ({
    ...s,
    grocery: {
      ...s.grocery,
      groups: s.grocery.groups.map((g, gi) =>
        gi !== groupIdx
          ? g
          : {
              ...g,
              items: g.items.map((it, ii) =>
                ii !== itemIdx
                  ? it
                  : { ...it, state: it.state === "bought" ? "open" : "bought" },
              ),
            },
      ),
    },
  }));
}

/** "Refresh status" — the mock provider reports the next lifecycle step. */
export function advanceOrder(): void {
  mutate((s) => {
    const order = s.grocery.order;
    if (!order || order.at >= order.steps.length - 1) return s;
    const at = order.at + 1;
    const out: StoreState = {
      ...s,
      grocery: {
        ...s.grocery,
        order: { ...order, at, state: order.steps[at] },
      },
    };
    return at === order.steps.length - 1
      ? pushNotification(out, "order", `${order.provider} order delivered`)
      : out;
  });
}

export function cancelOrder(): void {
  mutate((s) => {
    const order = s.grocery.order;
    if (!order) return s;
    return pushNotification(
      { ...s, grocery: { ...s.grocery, order: null } },
      "order",
      `${order.provider} order cancelled`,
    );
  });
}

export function resolveSubstitution(accept: boolean): void {
  mutate((s) => {
    const sub = s.grocery.substitution;
    if (!sub) return s;
    if (!accept) {
      return pushNotification(
        { ...s, grocery: { ...s.grocery, substitution: null } },
        "grocery",
        `Substitution rejected — ${sub.targetItem.toLowerCase()} stays on the list`,
      );
    }
    const groups = s.grocery.groups.map((g) => ({
      ...g,
      items: g.items.map((it) =>
        it.n === sub.targetItem
          ? {
              ...it,
              n: sub.replacement.n,
              q: sub.replacement.q,
              price: sub.replacement.price,
              note: "substituted — out of stock",
            }
          : it,
      ),
    }));
    return pushNotification(
      { ...s, grocery: { ...s.grocery, groups, substitution: null } },
      "grocery",
      `Substitution accepted — ${sub.replacement.n.toLowerCase()} replaces ${sub.targetItem.toLowerCase()}`,
    );
  });
}

/* ---- recipes: shared machinery -----------------------------------------------------------
 * Contract shapes throughout (design/frontend/pages/recipes.md +
 * recipe-detail.md). Every write returns through the same channels the real
 * API would: version appends bump RecipeDto.currentVersion + optimisticVersion
 * and re-hydrate currentVersionBody; guard failures surface as status-code
 * toasts per the specs' §8/§11 maps.
 */

function findRecipe(s: StoreState, recipeId: string): RecipeDto | undefined {
  return s.recipes.find((r) => r.id === recipeId);
}

function replaceRecipe(s: StoreState, next: RecipeDto): StoreState {
  return {
    ...s,
    recipes: s.recipes.map((r) => (r.id === next.id ? next : r)),
  };
}

/** Versions of one branch, ascending (GET …/versions equivalent). */
export function versionsFor(
  s: StoreState,
  recipeId: string,
  branchId: string,
): RecipeVersionDto[] {
  return s.recipeData.versions[recipeId]?.[branchId] ?? [];
}

export function currentVersionOf(
  s: StoreState,
  recipe: RecipeDto,
): RecipeVersionDto | undefined {
  const branchId = recipe.currentBranchId ?? mainBranchId(recipe.id);
  const list = versionsFor(s, recipe.id, branchId);
  return list[list.length - 1];
}

interface VersionBody {
  ingredients: RecipeVersionDto["ingredients"];
  methodSteps: RecipeVersionDto["methodSteps"];
  metadata: RecipeVersionDto["metadata"];
  tags: RecipeVersionDto["tags"];
}

let versionSeq = 100;

/**
 * Append a new version on a branch; bumps the branch + recipe counters and —
 * when the branch is current — re-hydrates currentVersionBody. Body-changing
 * writes flip nutritionStatus back to PENDING (imported/edited nutrition is
 * always recomputed internally — HLD rule).
 */
function appendVersion(
  s: StoreState,
  recipeId: string,
  branchId: string,
  body: VersionBody,
  trigger: RecipeVersionDto["trigger"],
  changeReason: string | null,
  actor: string = SELF_ACTOR,
): { state: StoreState; version: RecipeVersionDto } | null {
  const recipe = findRecipe(s, recipeId);
  const list = versionsFor(s, recipeId, branchId);
  const parent = list[list.length - 1];
  if (!recipe || !parent) return null;
  const version: RecipeVersionDto = {
    id: `${recipeId}-gen-${++versionSeq}`,
    branchId,
    versionNumber: parent.versionNumber + 1,
    parentVersionId: parent.id,
    trigger,
    changeReason,
    embeddingStatus: "PENDING",
    createdAt: nowIso(),
    createdByActor: actor,
    adapterTraceId: null,
    ...body,
    appliedSubstitutionIds: null,
  };
  const isCurrentBranch =
    (recipe.currentBranchId ?? mainBranchId(recipeId)) === branchId;
  const nextDto: RecipeDto = {
    ...recipe,
    currentVersion: isCurrentBranch ? version.versionNumber : recipe.currentVersion,
    currentVersionBody: isCurrentBranch ? version : recipe.currentVersionBody,
    nutritionStatus: "PENDING",
    optimisticVersion: recipe.optimisticVersion + 1,
    updatedAt: nowIso(),
    branches: recipe.branches.map((b) =>
      b.id === branchId
        ? { ...b, currentVersion: version.versionNumber, version: b.version + 1 }
        : b,
    ),
  };
  const state: StoreState = {
    ...replaceRecipe(s, nextDto),
    recipeData: {
      ...s.recipeData,
      versions: {
        ...s.recipeData.versions,
        [recipeId]: {
          ...s.recipeData.versions[recipeId],
          [branchId]: [...list, version],
        },
      },
    },
  };
  return { state, version };
}

/* ---- recipes: catalogue state machine (recipes.md §5) -------------------------------- */

/** POST /recipes/{id}/promote — flip-in-place SYSTEM → USER (one tap). */
export function promoteRecipe(recipeId: string): boolean {
  const recipe = findRecipe(state, recipeId);
  if (!recipe) {
    pushToast("404 — recipe no longer exists", "warn");
    return false;
  }
  if (recipe.catalogue === "USER") {
    pushToast("422 — already in your library", "warn");
    return false;
  }
  if (recipe.archivedAt) {
    pushToast("422 — unarchive the recipe before promoting it", "warn");
    return false;
  }
  mutate((s) => {
    const r = findRecipe(s, recipeId);
    if (!r || r.catalogue !== "SYSTEM") return s;
    return pushNotification(
      replaceRecipe(s, {
        ...r,
        catalogue: "USER",
        optimisticVersion: r.optimisticVersion + 1,
        updatedAt: nowIso(),
      }),
      "recipe",
      `${r.name} added to your library — versions and ratings preserved`,
    );
  });
  return true;
}

/** POST /recipes/{id}/demote — flip to SYSTEM; data preserved, not a delete. */
export function demoteRecipe(recipeId: string): void {
  const recipe = findRecipe(state, recipeId);
  if (!recipe) {
    pushToast("404 — recipe no longer exists", "warn");
    return;
  }
  if (recipe.catalogue === "SYSTEM") {
    pushToast("422 — already in the recipe pool", "warn");
    return;
  }
  mutate((s) => {
    const r = findRecipe(s, recipeId);
    if (!r || r.catalogue !== "USER") return s;
    return pushNotification(
      replaceRecipe(s, {
        ...r,
        catalogue: "SYSTEM",
        optimisticVersion: r.optimisticVersion + 1,
        updatedAt: nowIso(),
      }),
      "recipe",
      `${r.name} moved to the recipe pool — your versions are preserved`,
    );
  });
}

/** POST /recipes/{id}/archive — idempotent (re-archive is a 204 no-op). */
export function archiveRecipe(recipeId: string): void {
  mutate((s) => {
    const r = findRecipe(s, recipeId);
    if (!r || r.archivedAt) return s; // idempotent
    return replaceRecipe(s, {
      ...r,
      archivedAt: nowIso(),
      optimisticVersion: r.optimisticVersion + 1,
      updatedAt: nowIso(),
    });
  });
}

export function unarchiveRecipe(recipeId: string): void {
  mutate((s) => {
    const r = findRecipe(s, recipeId);
    if (!r || !r.archivedAt) return s; // idempotent
    return replaceRecipe(s, {
      ...r,
      archivedAt: null,
      optimisticVersion: r.optimisticVersion + 1,
      updatedAt: nowIso(),
    });
  });
}

/* ---- recipes: dedup gate + create/import (recipes.md §4) ------------------------------ */

const DEDUP_THRESHOLD = 0.8;

export interface DedupHit {
  candidateRecipeId: string;
  ingredientOverlap: number;
}

/** ≥80 % Jaccard overlap of normalised ingredient-mapping-key sets. */
function findDedupCandidate(
  s: StoreState,
  req: CreateRecipeRequest,
): DedupHit | null {
  const keys = new Set(
    req.ingredients.map((i) => i.ingredientMappingKey.trim().toLowerCase()),
  );
  let best: DedupHit | null = null;
  for (const r of s.recipes) {
    if (r.deletedAt || !r.currentVersionBody) continue;
    const theirs = new Set(
      r.currentVersionBody.ingredients.map((i) =>
        i.ingredientMappingKey.trim().toLowerCase(),
      ),
    );
    const intersection = [...keys].filter((k) => theirs.has(k)).length;
    const union = new Set([...keys, ...theirs]).size;
    if (union === 0) continue;
    const overlap = Math.round((intersection / union) * 100) / 100;
    if (overlap >= DEDUP_THRESHOLD && (!best || overlap > best.ingredientOverlap)) {
      best = { candidateRecipeId: r.id, ingredientOverlap: overlap };
    }
  }
  return best;
}

let recipeSeq = 0;

function slugify(text: string): string {
  return (
    text
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 48) || "recipe"
  );
}

function materialiseRecipe(
  s: StoreState,
  req: CreateRecipeRequest,
  opts: {
    quality: RecipeDto["dataQuality"];
    sourceUrl?: string | null;
    extractionMethod?: string | null;
  },
): { state: StoreState; recipeId: string } {
  let id = slugify(req.name);
  if (findRecipe(s, id)) id = `${id}-${++recipeSeq}`;
  const built = buildRecipe({
    id,
    name: req.name,
    desc: req.description ?? "",
    catalogue: "USER", // imports/creates always land in the caller's catalogue
    quality: opts.quality,
    nutrition: "PENDING", // external nutrition discarded; recalculated internally
    img: null,
    cuisine: req.metadata.cuisine ?? "—",
    prep: req.metadata.prepTimeMins,
    cook: req.metadata.cookTimeMins,
    servings: req.metadata.servings,
    mealTypes: req.metadata.mealTypes ?? [],
    equipment: req.metadata.equipmentRequired ?? [],
    fridgeDays: req.metadata.fridgeDays ?? undefined,
    freezerWeeks: req.metadata.freezerWeeks ?? undefined,
    packable: req.metadata.packable ?? false,
    tags: req.tags
      ? {
          protein: req.tags.protein ?? null,
          cookingMethod: req.tags.cookingMethod ?? null,
          complexity: req.tags.complexity ?? null,
          flavourProfile: req.tags.flavourProfile ?? [],
          dietaryFlags: req.tags.dietaryFlags ?? [],
        }
      : null,
    ing: req.ingredients.map((i) => ({
      k: i.ingredientMappingKey,
      n: i.displayName,
      q: i.quantity ?? null,
      u: i.unit ?? null,
      prep: i.preparation ?? null,
      opt: i.optional ?? false,
    })),
    steps: req.method.map((m) => ({
      t: m.instruction,
      m: m.durationMinutes ?? undefined,
    })),
    createdAt: nowIso(),
    trigger: opts.quality === "IMPORTED" ? "IMPORT" : "MANUAL_CREATE",
  });
  let next: StoreState = {
    ...s,
    recipes: [built.dto, ...s.recipes],
    recipeData: {
      ...s.recipeData,
      versions: { ...s.recipeData.versions, [id]: built.versions },
    },
  };
  if (opts.sourceUrl !== undefined) {
    next = {
      ...next,
      recipeData: {
        ...next.recipeData,
        provenance: {
          ...next.recipeData.provenance,
          [id]: {
            id: `imp-${id}`,
            recipeId: id,
            sourceType: opts.sourceUrl ? "URL" : "MANUAL",
            sourceUrl: opts.sourceUrl ?? null,
            sourcePayload: null,
            extractionMethod: opts.extractionMethod ?? null,
            duplicateOfRecipeId: null,
            importedAt: nowIso(),
            importedByUserId: MOCK_USER_ID,
          },
        },
      },
    };
  }
  return { state: next, recipeId: id };
}

export type CreateRecipeOutcome =
  | { kind: "created"; recipeId: string }
  | { kind: "duplicate"; hit: DedupHit };

/** POST /recipes — manual create; runs the dedup gate (422 → §4c dialog). */
export function createRecipeManual(req: CreateRecipeRequest): CreateRecipeOutcome {
  const hit = findDedupCandidate(state, req);
  if (hit) return { kind: "duplicate", hit };
  let outcome: CreateRecipeOutcome = { kind: "duplicate", hit: { candidateRecipeId: "", ingredientOverlap: 0 } };
  mutate((s) => {
    const made = materialiseRecipe(s, req, { quality: "USER_VERIFIED" });
    outcome = { kind: "created", recipeId: made.recipeId };
    return pushNotification(
      made.state,
      "recipe",
      `${req.name} added to your library — nutrition calculating`,
    );
  });
  return outcome;
}

export type ImportPreviewOutcome =
  | { kind: "preview"; preview: RecipeImportPreview }
  | { kind: "failure"; failureReason: string; detail: string };

let previewSeq = 0;

/**
 * POST /imports/preview-url (#3) — deliberately non-transactional read.
 * Mock extraction routes by URL: the seeded DEDUP_DEMO_URL previews a
 * near-duplicate of chicken-stir-fry; substrings simulate the §4a
 * failureReason vocabulary (timeout / blocked / broken / no-recipe / huge).
 */
export function previewImportFromUrl(url: string): ImportPreviewOutcome {
  const u = url.toLowerCase();
  if (u.includes("timeout")) {
    return { kind: "failure", failureReason: "fetch_timeout", detail: "Read timed out after 10 s" };
  }
  if (u.includes("blocked")) {
    return { kind: "failure", failureReason: "fetch_4xx_403", detail: "The site returned HTTP 403" };
  }
  if (u.includes("broken")) {
    return { kind: "failure", failureReason: "fetch_5xx_500", detail: "The site returned HTTP 500" };
  }
  if (u.includes("no-recipe") || u.includes("essay")) {
    return {
      kind: "failure",
      failureReason: "no_extractor_matched",
      detail: "No recipe markup (json-ld / microdata / known selectors) found",
    };
  }
  if (u.includes("huge")) {
    return { kind: "failure", failureReason: "oversize", detail: "Page exceeded the 4 MB extraction cap" };
  }
  const parsedRecipe =
    url === DEDUP_DEMO_URL ? DEDUP_PARSED_RECIPE : GENERIC_PARSED_RECIPE;
  const hit = findDedupCandidate(state, parsedRecipe);
  return {
    kind: "preview",
    preview: {
      previewToken: `pt-${++previewSeq}`,
      parsedRecipe,
      sourceUrl: url,
      extractionMethod: url === DEDUP_DEMO_URL ? "microdata" : "json_ld",
      validationWarnings: url === DEDUP_DEMO_URL ? [] : GENERIC_PARSE_WARNINGS,
      dedupCandidate: hit
        ? { recipeId: hit.candidateRecipeId, ingredientOverlap: hit.ingredientOverlap }
        : null,
    },
  };
}

/** POST /imports/preview-html (#4) — the in-app-browser "Save recipe" path. */
export function previewImportFromHtml(url: string, html: string): ImportPreviewOutcome {
  if (html.trim().length < 40) {
    return {
      kind: "failure",
      failureReason: "no_extractor_matched",
      detail: "Supplied markup too small to contain a recipe",
    };
  }
  const hit = findDedupCandidate(state, GENERIC_PARSED_RECIPE);
  return {
    kind: "preview",
    preview: {
      previewToken: `pt-${++previewSeq}`,
      parsedRecipe: GENERIC_PARSED_RECIPE,
      sourceUrl: url,
      extractionMethod: "common_selectors",
      validationWarnings: GENERIC_PARSE_WARNINGS,
      dedupCandidate: hit
        ? { recipeId: hit.candidateRecipeId, ingredientOverlap: hit.ingredientOverlap }
        : null,
    },
  };
}

/** POST /imports/confirm (#5) — the reviewed body is authoritative; dedup
 *  runs BEFORE persistence (422 recipe-import-duplicate → §4c dialog). */
export function confirmImport(
  req: CreateRecipeRequest,
  sourceUrl: string,
  extractionMethod: string | null,
): CreateRecipeOutcome {
  const hit = findDedupCandidate(state, req);
  if (hit) return { kind: "duplicate", hit };
  let outcome: CreateRecipeOutcome = { kind: "duplicate", hit: { candidateRecipeId: "", ingredientOverlap: 0 } };
  mutate((s) => {
    const made = materialiseRecipe(s, req, {
      quality: "IMPORTED",
      sourceUrl,
      extractionMethod,
    });
    outcome = { kind: "created", recipeId: made.recipeId };
    return pushNotification(
      made.state,
      "recipe",
      `${req.name} imported — nutrition calculating`,
    );
  });
  return outcome;
}

/* ---- recipes: branches (recipe-detail.md §5a) ----------------------------------------- */

/** POST /recipes/{id}/branches. Returns the new branch id, or null on guard. */
export function createVariantBranch(
  recipeId: string,
  req: CreateBranchRequest,
): string | null {
  const recipe = findRecipe(state, recipeId);
  if (!recipe) {
    pushToast("404 — recipe no longer exists", "warn");
    return null;
  }
  if (recipe.catalogue === "SYSTEM") {
    pushToast("422 — pool recipes can't be branched; add to your library first", "warn");
    return null;
  }
  if (req.name === "main") {
    pushToast("422 — 'main' is reserved", "warn");
    return null;
  }
  if (!/^[a-z0-9-]+$/.test(req.name)) {
    pushToast("400 — branch name must be a slug (a–z, 0–9, hyphen)", "warn");
    return null;
  }
  if (recipe.branches.some((b) => b.name === req.name)) {
    pushToast(`409 — branch name '${req.name}' is taken`, "warn");
    return null;
  }
  const allVersions = Object.values(
    state.recipeData.versions[recipeId] ?? {},
  ).flat();
  const forkPoint = allVersions.find((v) => v.id === req.branchPointVersionId);
  if (!forkPoint) {
    pushToast("422 — fork-point version doesn't belong to this recipe", "warn");
    return null;
  }
  const branchId = `${recipeId}-${req.name}`;
  mutate((s) => {
    const r = findRecipe(s, recipeId);
    if (!r) return s;
    const startVersion: RecipeVersionDto = {
      id: `${branchId}-v1`,
      branchId,
      versionNumber: 1,
      parentVersionId: forkPoint.id, // cross-branch lineage (branch-start row)
      trigger: "BRANCH_CREATION",
      changeReason: req.reason,
      embeddingStatus: "PENDING",
      createdAt: nowIso(),
      createdByActor: SELF_ACTOR,
      adapterTraceId: null,
      ingredients: ingredientsFromRequest(req.body.ingredients, forkPoint.ingredients),
      methodSteps: stepsFromRequest(req.body.method),
      metadata: {
        servings: req.body.metadata.servings,
        prepTimeMins: req.body.metadata.prepTimeMins,
        cookTimeMins: req.body.metadata.cookTimeMins,
        totalTimeMins: req.body.metadata.totalTimeMins,
        equipmentRequired: req.body.metadata.equipmentRequired ?? [],
        fridgeDays: req.body.metadata.fridgeDays ?? null,
        freezerWeeks: req.body.metadata.freezerWeeks ?? null,
        packable: req.body.metadata.packable ?? false,
        cuisine: req.body.metadata.cuisine ?? null,
        mealTypes: req.body.metadata.mealTypes ?? [],
      },
      tags: req.body.tags
        ? {
            protein: req.body.tags.protein ?? null,
            cookingMethod: req.body.tags.cookingMethod ?? null,
            complexity: req.body.tags.complexity ?? null,
            flavourProfile: req.body.tags.flavourProfile ?? [],
            dietaryFlags: req.body.tags.dietaryFlags ?? [],
          }
        : null,
      appliedSubstitutionIds: null,
    };
    // NOTE: creating a branch does NOT switch currentBranchId (§5a).
    const next: RecipeDto = {
      ...r,
      optimisticVersion: r.optimisticVersion + 1,
      updatedAt: nowIso(),
      branches: [
        ...r.branches,
        {
          id: branchId,
          recipeId,
          parentBranchId: forkPoint.branchId,
          branchPointVersionId: forkPoint.id,
          name: req.name,
          label: req.label ?? null,
          reason: req.reason,
          currentVersion: 1,
          divergenceScore: 0.12,
          createdAt: nowIso(),
          createdByActor: SELF_ACTOR,
          adapterTraceId: null,
          version: 1,
        },
      ],
    };
    return pushNotification(
      {
        ...replaceRecipe(s, next),
        recipeData: {
          ...s.recipeData,
          versions: {
            ...s.recipeData.versions,
            [recipeId]: {
              ...s.recipeData.versions[recipeId],
              [branchId]: [startVersion],
            },
          },
        },
      },
      "recipe",
      `${r.name} forked as '${req.label ?? req.name}' from v${forkPoint.versionNumber}`,
    );
  });
  return branchId;
}

/* ---- recipes: edit + revert (recipe-detail.md §4b/§5b) -------------------------------- */

export type EditOutcome = "ok" | "conflict" | "noop" | "catalogue" | "missing";

/** PUT /recipes/{id} — full replacement; new version, trigger MANUAL_EDIT. */
export function editRecipe(
  recipeId: string,
  req: UpdateRecipeManualEditRequest,
): EditOutcome {
  const recipe = findRecipe(state, recipeId);
  if (!recipe) return "missing";
  if (recipe.catalogue === "SYSTEM") {
    pushToast("422 recipe-catalogue-violation — promote to your library first", "warn");
    return "catalogue";
  }
  if (req.expectedOptimisticVersion !== recipe.optimisticVersion) {
    pushToast("409 — recipe changed since you opened it; reloaded", "warn");
    return "conflict";
  }
  const current = currentVersionOf(state, recipe);
  if (!current) return "missing";
  const branchId = recipe.currentBranchId ?? mainBranchId(recipeId);
  let changed = recipe.name !== req.name || (recipe.description ?? "") !== (req.description ?? "");
  const body: VersionBody = {
    ingredients: ingredientsFromRequest(req.ingredients, current.ingredients),
    methodSteps: stepsFromRequest(req.method),
    metadata: {
      servings: req.metadata.servings,
      prepTimeMins: req.metadata.prepTimeMins,
      cookTimeMins: req.metadata.cookTimeMins,
      totalTimeMins: req.metadata.totalTimeMins,
      equipmentRequired: req.metadata.equipmentRequired ?? [],
      fridgeDays: req.metadata.fridgeDays ?? null,
      freezerWeeks: req.metadata.freezerWeeks ?? null,
      packable: req.metadata.packable ?? false,
      cuisine: req.metadata.cuisine ?? null,
      mealTypes: req.metadata.mealTypes ?? [],
    },
    tags: req.tags
      ? {
          protein: req.tags.protein ?? null,
          cookingMethod: req.tags.cookingMethod ?? null,
          complexity: req.tags.complexity ?? null,
          flavourProfile: req.tags.flavourProfile ?? [],
          dietaryFlags: req.tags.dietaryFlags ?? [],
        }
      : null,
  };
  const probe = computeDiff(current, {
    ...current,
    ingredients: body.ingredients,
    methodSteps: body.methodSteps,
    metadata: body.metadata,
    tags: body.tags,
  });
  changed =
    changed ||
    probe.ingredientChanges.length > 0 ||
    probe.methodChanges.length > 0 ||
    probe.metadataChanges.length > 0 ||
    probe.tagChanges.length > 0;
  if (!changed) {
    pushToast("400 — nothing changed; no version written", "warn");
    return "noop";
  }
  mutate((s) => {
    const r = findRecipe(s, recipeId);
    if (!r) return s;
    const appended = appendVersion(s, recipeId, branchId, body, "MANUAL_EDIT", req.changeReason);
    if (!appended) return s;
    const withName = replaceRecipe(appended.state, {
      ...(findRecipe(appended.state, recipeId) as RecipeDto),
      name: req.name,
      description: req.description ?? null,
    });
    return pushNotification(
      withName,
      "recipe",
      `${req.name} edited — v${appended.version.versionNumber} created`,
    );
  });
  return "ok";
}

/** POST /recipes/{id}/versions/revert — writes a NEW version cloning the
 *  target (trigger REVERT); history is never rewritten. */
export function revertRecipe(recipeId: string, req: RevertToVersionRequest): boolean {
  const recipe = findRecipe(state, recipeId);
  if (!recipe) return false;
  if (recipe.catalogue === "SYSTEM") {
    pushToast("422 recipe-catalogue-violation — promote to your library first", "warn");
    return false;
  }
  if (req.expectedRecipeOptimisticVersion !== recipe.optimisticVersion) {
    pushToast("409 — recipe changed since you opened it; reloaded", "warn");
    return false;
  }
  const list = versionsFor(state, recipeId, req.branchId);
  const target = list.find((v) => v.versionNumber === req.versionNumber);
  const head = list[list.length - 1];
  if (!target || !head) {
    pushToast("422 — version not found on that branch", "warn");
    return false;
  }
  if (target.id === head.id) {
    pushToast("400 — that's already the current version; nothing to revert", "warn");
    return false;
  }
  mutate((s) => {
    const appended = appendVersion(
      s,
      recipeId,
      req.branchId,
      {
        ingredients: target.ingredients,
        methodSteps: target.methodSteps,
        metadata: target.metadata,
        tags: target.tags,
      },
      "REVERT",
      `Revert to v${target.versionNumber}`,
    );
    if (!appended) return s;
    return pushNotification(
      appended.state,
      "recipe",
      `${recipe.name} reverted — v${appended.version.versionNumber} copies v${target.versionNumber}`,
    );
  });
  return true;
}

/* ---- recipes: substitutions state machine (recipe-detail.md §6) ----------------------- */

let subSeq = 0;

function withSubstitutions(
  s: StoreState,
  recipeId: string,
  fn: (rows: RecipeSubstitutionDto[]) => RecipeSubstitutionDto[],
): StoreState {
  return {
    ...s,
    recipeData: {
      ...s.recipeData,
      substitutions: {
        ...s.recipeData.substitutions,
        [recipeId]: fn(s.recipeData.substitutions[recipeId] ?? []),
      },
    },
  };
}

/** POST /recipes/{id}/substitutions — lands PROPOSED. */
export function proposeSubstitution(
  recipeId: string,
  req: CreateSubstitutionRequest,
): boolean {
  const recipe = findRecipe(state, recipeId);
  if (!recipe) return false;
  if (recipe.catalogue === "SYSTEM") {
    pushToast("422 — pool recipes can't take substitutions; promote first", "warn");
    return false;
  }
  const allVersions = Object.values(state.recipeData.versions[recipeId] ?? {}).flat();
  const version = allVersions.find((v) => v.id === req.versionId);
  if (!version) {
    pushToast("422 — version not found on this recipe", "warn");
    return false;
  }
  if (
    !version.ingredients.some(
      (i) => i.ingredientMappingKey === req.original.ingredientMappingKey,
    )
  ) {
    pushToast("422 — original ingredient isn't on that version", "warn");
    return false;
  }
  mutate((s) =>
    withSubstitutions(s, recipeId, (rows) => [
      ...rows,
      {
        id: `sub-new-${++subSeq}`,
        recipeId,
        versionId: req.versionId,
        branchId: version.branchId,
        original: req.original,
        substitute: req.substitute,
        reason: req.reason,
        constraintRef: req.constraintRef ?? null,
        methodOverlay: req.methodOverlay ?? null,
        notes: req.notes ?? null,
        temporary: req.temporary,
        applicationCount: 0,
        lastAppliedAt: null,
        state: "PROPOSED",
        promotedToVersionId: null,
        createdAt: nowIso(),
        createdByActor: SELF_ACTOR,
        adapterTraceId: null,
        version: 1,
      },
    ]),
  );
  return true;
}

export type SubstitutionAction = "accept" | "reject" | "promote-to-version";

/**
 * POST /substitutions/{subId}/{action}. Shipped state machine: PROPOSED →
 * ACCEPTED | REJECTED; ACCEPTED → SUPERSEDED on promote; REJECTED → ACCEPTED
 * is legal (re-accept); only SUPERSEDED is hard-terminal (§11 Q3).
 */
export function actOnSubstitution(
  recipeId: string,
  subId: string,
  action: SubstitutionAction,
  body: { expectedVersion: number; reason?: string | null; changeReason?: string },
): void {
  const rows = state.recipeData.substitutions[recipeId] ?? [];
  const sub = rows.find((x) => x.id === subId);
  if (!sub) {
    pushToast("404 — substitution no longer exists", "warn");
    return;
  }
  if (sub.version !== body.expectedVersion) {
    pushToast("409 — substitution changed elsewhere; row re-fetched", "warn");
    return;
  }
  if (sub.state === "SUPERSEDED") {
    pushToast("422 — already made permanent (terminal)", "warn");
    return;
  }
  if (action === "accept") {
    if (sub.state === "ACCEPTED") return; // idempotent 200 no-op, no bump
    mutate((s) =>
      withSubstitutions(s, recipeId, (xs) =>
        xs.map((x) =>
          x.id === subId
            ? { ...x, state: "ACCEPTED", version: x.version + 1 }
            : x,
        ),
      ),
    );
    return;
  }
  if (action === "reject") {
    if (sub.state === "REJECTED") return;
    mutate((s) =>
      withSubstitutions(s, recipeId, (xs) =>
        xs.map((x) =>
          x.id === subId
            ? { ...x, state: "REJECTED", version: x.version + 1 }
            : x,
        ),
      ),
    );
    return;
  }
  // promote-to-version
  if (sub.state !== "ACCEPTED") {
    pushToast("422 — only an accepted substitution can be made permanent", "warn");
    return;
  }
  if (!body.changeReason?.trim()) {
    pushToast("400 — a change note is required to make a swap permanent", "warn");
    return;
  }
  mutate((s) => {
    const recipe = findRecipe(s, recipeId);
    const version = Object.values(s.recipeData.versions[recipeId] ?? {})
      .flat()
      .find((v) => v.id === sub.versionId);
    if (!recipe || !version) return s;
    const ingredients = version.ingredients.map((i) =>
      i.ingredientMappingKey === sub.original.ingredientMappingKey
        ? {
            ...i,
            id: `ing-promo-${++subSeq}`,
            ingredientMappingKey: sub.substitute.ingredientMappingKey,
            displayName:
              sub.substitute.ingredientMappingKey.charAt(0).toUpperCase() +
              sub.substitute.ingredientMappingKey.slice(1),
            quantity: sub.substitute.quantity,
            unit: sub.substitute.unit,
          }
        : i,
    );
    const overlayByStep = new Map(
      (sub.methodOverlay ?? []).map((l) => [l.step, l.instruction]),
    );
    const methodSteps = version.methodSteps.map((m) =>
      overlayByStep.has(m.stepNumber)
        ? { ...m, instruction: overlayByStep.get(m.stepNumber) as string }
        : m,
    );
    const appended = appendVersion(
      s,
      recipeId,
      version.branchId,
      { ingredients, methodSteps, metadata: version.metadata, tags: version.tags },
      "SUBSTITUTION_PROMOTION",
      body.changeReason ?? null,
    );
    if (!appended) return s;
    const out = withSubstitutions(appended.state, recipeId, (xs) =>
      xs.map((x) =>
        x.id === subId
          ? {
              ...x,
              state: "SUPERSEDED" as const,
              promotedToVersionId: appended.version.id,
              version: x.version + 1,
            }
          : x,
      ),
    );
    return pushNotification(
      out,
      "recipe",
      `${recipe.name}: swap made permanent — v${appended.version.versionNumber} created`,
    );
  });
}

/* ---- recipes: ratings (recipe-detail.md §7) -------------------------------------------- */

let userRatingSeq = 500;

function withRatings(
  s: StoreState,
  recipeId: string,
  fn: (rows: RecipeRatingDto[]) => RecipeRatingDto[],
): StoreState {
  return {
    ...s,
    recipeData: {
      ...s.recipeData,
      ratings: {
        ...s.recipeData.ratings,
        [recipeId]: fn(s.recipeData.ratings[recipeId] ?? []),
      },
    },
  };
}

export function myRatingFor(
  s: StoreState,
  recipeId: string,
  versionId: string,
): RecipeRatingDto | undefined {
  return (s.recipeData.ratings[recipeId] ?? []).find(
    (r) => r.userId === MOCK_USER_ID && r.versionId === versionId,
  );
}

/**
 * POST /recipes/{id}/ratings — one per user per version. When mine already
 * exists the real call 409s; the page switches to the PUT path silently
 * (§7b), which this mock performs in one step.
 */
export function submitRating(recipeId: string, req: CreateRatingRequest): void {
  const mine = myRatingFor(state, recipeId, req.versionId);
  if (mine) {
    updateRating(recipeId, mine.id, req, mine.optimisticVersion);
    pushToast("Already rated this version — updated instead (409 → PUT)");
    return;
  }
  mutate((s) =>
    withRatings(s, recipeId, (rows) => [
      {
        id: `rate-u${++userRatingSeq}`,
        recipeId,
        versionId: req.versionId,
        userId: MOCK_USER_ID,
        householdId: null,
        slotId: req.slotId ?? null,
        taste: req.taste,
        effortWorthIt: req.effortWorthIt ?? null,
        portionFit: req.portionFit ?? null,
        repeatValue: req.repeatValue ?? null,
        aggregate: ratingAggregate(req),
        notes: req.notes ?? null,
        traceId: null,
        optimisticVersion: 1,
        createdAt: nowIso(),
        updatedAt: nowIso(),
      },
      ...rows,
    ]),
  );
}

/** PUT /recipes/{id}/ratings/{ratingId}. */
export function updateRating(
  recipeId: string,
  ratingId: string,
  req: CreateRatingRequest,
  expectedVersion: number,
): void {
  const row = (state.recipeData.ratings[recipeId] ?? []).find((r) => r.id === ratingId);
  if (!row) {
    pushToast("404 — rating no longer exists", "warn");
    return;
  }
  if (row.optimisticVersion !== expectedVersion) {
    pushToast("409 — your rating changed elsewhere; reloaded", "warn");
    return;
  }
  mutate((s) =>
    withRatings(s, recipeId, (rows) =>
      rows.map((r) =>
        r.id === ratingId
          ? {
              ...r,
              taste: req.taste,
              effortWorthIt: req.effortWorthIt ?? null,
              portionFit: req.portionFit ?? null,
              repeatValue: req.repeatValue ?? null,
              aggregate: ratingAggregate(req),
              notes: req.notes ?? null,
              optimisticVersion: r.optimisticVersion + 1,
              updatedAt: nowIso(),
            }
          : r,
      ),
    ),
  );
}

/** DELETE /recipes/{id}/ratings/{ratingId} → 204. */
export function deleteRating(recipeId: string, ratingId: string): void {
  mutate((s) =>
    withRatings(s, recipeId, (rows) => rows.filter((r) => r.id !== ratingId)),
  );
}

/* ---- recipes: image upload (#16) -------------------------------------------------------- */

/** POST /recipes/{id}/image — size/MIME pre-checks live in the dropzone. */
export function setRecipeImage(recipeId: string, objectUrl: string): void {
  mutate((s) => {
    const r = findRecipe(s, recipeId);
    if (!r || r.catalogue === "SYSTEM") return s; // 403 — owner only
    return replaceRecipe(s, {
      ...r,
      imageUrl: objectUrl,
      optimisticVersion: r.optimisticVersion + 1,
      updatedAt: nowIso(),
    });
  });
}

/* ---- recipes: nutrition recalculate (n1, nutrition.md §7) -------------------------------- */

/**
 * POST /nutrition/recipes/{id}/versions/{vid}/recalculate. Deterministic fake
 * numbers; needs-review rows surface as unmapped[] and hold status at
 * partial. This is the ONLY way the mock (or the contract) yields
 * per-serving numbers — recipe-detail.md §11 Q1.
 */
export function recalculateNutrition(recipeId: string): RecipeNutritionResultDto | null {
  const recipe = findRecipe(state, recipeId);
  const version = recipe && currentVersionOf(state, recipe);
  if (!recipe || !version) return null;
  const h = hashCode(version.id);
  const unmapped = version.ingredients
    .filter((i) => i.needsReview)
    .map((i) => ({
      name: i.displayName,
      reason: "USDA match below the 0.70 confidence floor",
      confidence: i.mappingConfidence ?? 0,
    }));
  const result: RecipeNutritionResultDto = {
    recipeId,
    caloriesPerServing: 380 + (h % 260),
    proteinPerServingG: Math.round((14 + ((h >> 3) % 26)) * 10) / 10,
    carbsPerServingG: Math.round((30 + ((h >> 5) % 50)) * 10) / 10,
    fatPerServingG: Math.round((9 + ((h >> 7) % 22)) * 10) / 10,
    fibrePerServingG: Math.round((3 + ((h >> 9) % 9)) * 10) / 10,
    microsPerServing: {
      sodium_mg: 300 + ((h >> 4) % 500),
      iron_mg: Math.round((1.5 + ((h >> 6) % 40) / 10) * 10) / 10,
    },
    nutritionStatus: unmapped.length > 0 ? "partial" : "calculated",
    unmapped,
  };
  mutate((s) => {
    const r = findRecipe(s, recipeId);
    if (!r) return s;
    return {
      ...replaceRecipe(s, {
        ...r,
        nutritionStatus: unmapped.length > 0 ? "PARTIAL" : "CALCULATED",
        updatedAt: nowIso(),
      }),
      recipeData: {
        ...s.recipeData,
        nutritionByVersion: {
          ...s.recipeData.nutritionByVersion,
          [version.id]: result,
        },
      },
    };
  });
  return result;
}

/* ---- adaptation: pending changes (recipe-detail.md §10, a1–a5) ---------------------------
 * Accept needs expectedOptimisticVersion from the DETAIL read — the list row
 * doesn't carry it (expand-then-accept, two calls; §11 Q5). Accept routes the
 * proposal through a NEW VERSION (trigger ADAPTATION_PIPELINE).
 */

function applyDiffToBody(
  version: RecipeVersionDto,
  diff: RecipeDiffDto,
): VersionBody {
  let ingredients = [...version.ingredients];
  for (const ch of diff.ingredientChanges) {
    if (ch.action === "MODIFIED" && ch.from?.ingredientMappingKey && ch.to) {
      const to = ch.to;
      ingredients = ingredients.map((i) =>
        i.ingredientMappingKey === ch.from?.ingredientMappingKey
          ? {
              ...i,
              quantity: to.quantity ?? i.quantity,
              unit: to.unit ?? i.unit,
              preparation: to.preparation ?? i.preparation,
              displayName: to.displayName ?? i.displayName,
            }
          : i,
      );
    } else if (ch.action === "ADDED" && ch.to?.ingredientMappingKey) {
      const to = ch.to;
      ingredients = [
        ...ingredients,
        {
          id: `ing-adapt-${++subSeq}`,
          lineOrder: ingredients.length,
          ingredientMappingKey: to.ingredientMappingKey as string,
          displayName: to.displayName ?? (to.ingredientMappingKey as string),
          quantity: to.quantity ?? null,
          unit: to.unit ?? null,
          preparation: to.preparation ?? null,
          optional: to.optional ?? false,
          needsReview: false,
          mappingConfidence: 0.9,
        },
      ];
    } else if (ch.action === "REMOVED" && ch.from?.ingredientMappingKey) {
      ingredients = ingredients.filter(
        (i) => i.ingredientMappingKey !== ch.from?.ingredientMappingKey,
      );
    }
  }
  let metadata = version.metadata;
  for (const ch of diff.metadataChanges) {
    if (metadata && ch.field === "servings" && typeof ch.to === "number") {
      metadata = { ...metadata, servings: ch.to };
    }
  }
  let methodSteps = version.methodSteps;
  for (const ch of diff.methodChanges) {
    if (ch.action === "MODIFIED" && ch.to) {
      const to = ch.to;
      methodSteps = methodSteps.map((m) =>
        m.stepNumber === ch.step ? { ...m, instruction: to } : m,
      );
    }
  }
  return { ingredients, methodSteps, metadata, tags: version.tags };
}

/** Best-effort read of the opaque proposedDiff as a RecipeDiffDto (§10 — the
 *  pipeline's shape matches in this mock; flagged Q6 in the spec). */
export function diffFromProposed(detail: PendingChangeDto): RecipeDiffDto | null {
  const d = detail.userEdits ?? detail.proposedDiff;
  if (!d || !Array.isArray((d as { ingredientChanges?: unknown }).ingredientChanges)) {
    return null;
  }
  return d as unknown as RecipeDiffDto;
}

function resolvePendingChange(
  s: StoreState,
  id: string,
  next: PendingChangeDto,
): StoreState {
  return {
    ...s,
    adaptation: {
      pendingChanges: s.adaptation.pendingChanges.filter((c) => c.id !== id),
      detailById: { ...s.adaptation.detailById, [id]: next },
      historyByRecipe: {
        ...s.adaptation.historyByRecipe,
        [next.recipeId]: [
          next,
          ...(s.adaptation.historyByRecipe[next.recipeId] ?? []),
        ],
      },
    },
  };
}

/**
 * POST /adaptation/pending-changes/{id}/accept. userEdits (opaque overlay)
 * null = as proposed; the modify-then-accept path passes the edited diff.
 */
export function acceptPendingChange(
  id: string,
  userEdits?: RecipeDiffDto | null,
  expectedOptimisticVersion?: number,
): void {
  const detail = state.adaptation.detailById[id];
  if (!detail || detail.status !== "PENDING") {
    pushToast("422 — this suggestion is no longer pending", "warn");
    return;
  }
  if (
    expectedOptimisticVersion !== undefined &&
    expectedOptimisticVersion !== detail.optimisticVersion
  ) {
    pushToast("409 — this suggestion changed; re-fetched", "warn");
    return;
  }
  mutate((s) => {
    const recipe = findRecipe(s, detail.recipeId);
    if (!recipe) return s;
    const branchId = detail.baseBranchId;
    const list = versionsFor(s, detail.recipeId, branchId);
    const base =
      list.find((v) => v.id === detail.baseVersionId) ?? list[list.length - 1];
    if (!base) return s;
    const effective =
      userEdits ?? diffFromProposed(detail) ?? {
        fromVersionId: base.id,
        toVersionId: base.id,
        ingredientChanges: [],
        methodChanges: [],
        metadataChanges: [],
        tagChanges: [],
      };
    const appended = appendVersion(
      s,
      detail.recipeId,
      branchId,
      applyDiffToBody(list[list.length - 1] ?? base, effective),
      "ADAPTATION_PIPELINE",
      detail.reasoning.split(".")[0],
      "adaptation_pipeline",
    );
    if (!appended) return s;
    const resolved: PendingChangeDto = {
      ...detail,
      status: userEdits ? "MODIFIED" : "ACCEPTED",
      userEdits: userEdits ? (userEdits as unknown as Record<string, unknown>) : null,
      acceptedVersionId: appended.version.id,
      resolvedAt: nowIso(),
      optimisticVersion: detail.optimisticVersion + 1,
    };
    return pushNotification(
      resolvePendingChange(appended.state, id, resolved),
      "recipe",
      `${recipe.name} updated — suggestion applied (v${appended.version.versionNumber} created)`,
    );
  });
}

/** POST /adaptation/pending-changes/{id}/reject (+optional reasonNote). */
export function rejectPendingChange(id: string, reasonNote?: string): void {
  const detail = state.adaptation.detailById[id];
  if (!detail || detail.status !== "PENDING") {
    pushToast("422 — this suggestion is no longer pending", "warn");
    return;
  }
  void reasonNote; // ≤200, audit-only — not displayed anywhere in v1
  mutate((s) =>
    resolvePendingChange(s, id, {
      ...detail,
      status: "REJECTED",
      resolvedAt: nowIso(),
      optimisticVersion: detail.optimisticVersion + 1,
    }),
  );
}

/* ---- pantry ------------------------------------------------------------------------------ */

export function adjustPantryQty(id: string, delta: number): void {
  mutate((s) => ({
    ...s,
    pantry: {
      ...s.pantry,
      items: s.pantry.items.map((it) =>
        it.id === id && !it.spoiled
          ? { ...it, qty: Math.max(0, it.qty + delta) }
          : it,
      ),
    },
  }));
}

/**
 * Mark a pantry item spoiled: logs waste and — when nothing is already
 * pending — raises a contract-shaped re-opt suggestion against the active
 * plan (PROVISIONS listener, cross-page liveliness). The suggestion's diff
 * lives server-side until accept (spec §8 Q2).
 */
export function markSpoiled(id: string): void {
  mutate((s) => {
    const item = s.pantry.items.find((it) => it.id === id);
    if (!item || item.spoiled) return s;

    const qtyLabel = item.unit ? `${item.qty} ${item.unit}` : `${item.qty}`;
    let out: StoreState = {
      ...s,
      pantry: {
        ...s.pantry,
        items: s.pantry.items.map((it) =>
          it.id === id ? { ...it, spoiled: true } : it,
        ),
        waste: {
          monthTotal:
            Math.round((s.pantry.waste.monthTotal + item.estCost) * 100) / 100,
          entries: [
            {
              name: `${item.name} ${qtyLabel}`,
              cost: `£${item.estCost.toFixed(2)}`,
              when: "Wed 10 June",
            },
            ...s.pantry.waste.entries,
          ],
        },
      },
    };

    const active = activePlanForWeek(out, CURRENT_WEEK_START);
    if (out.planner.suggestions.length === 0 && active) {
      const alreadyAffected = new Set(
        out.planner.suggestions.flatMap((x) => x.affectedSlotIds),
      );
      // Target the first future dinner still PLANNED (pinned slots immune).
      const target = active.days
        .filter((d) => d.date > MOCK_TODAY_ISO)
        .flatMap((d) => d.slots)
        .find(
          (sl) =>
            sl.kind === "DINNER" &&
            sl.state === "PLANNED" &&
            sl.pinnedReason == null &&
            sl.scheduledRecipe != null &&
            !alreadyAffected.has(sl.id),
        );
      if (target?.scheduledRecipe) {
        const sgId = `sg-${++suggestionSeq}`;
        const suggestion: ReoptSuggestionDto = {
          id: sgId,
          householdId: active.householdId,
          weekStartDate: active.weekStartDate,
          planId: active.id,
          triggerKind: "PROVISIONS",
          triggerEventId: `evt-spoil-${item.id}`,
          affectedSlotIds: [target.id],
          summary: `${item.name} marked spoiled`,
          status: "PENDING",
          expiresAt: `${addDaysIso(active.weekStartDate, 7)}T00:00:00Z`,
          createdAt: nowStamp(),
          resolvedAt: null,
        };
        out = {
          ...out,
          planner: {
            ...out.planner,
            suggestions: [suggestion, ...out.planner.suggestions],
            proposedBySuggestion: {
              ...out.planner.proposedBySuggestion,
              [sgId]: {
                schemaVersion: 1,
                changes: [
                  {
                    slotId: target.id,
                    oldRecipeId: target.scheduledRecipe.recipeId,
                    newRecipeId: "one-pot-tomato-orzo",
                    newRecipeVersionId: "one-pot-tomato-orzo-v1",
                    newRecipeBranchId: "one-pot-tomato-orzo-main",
                    newServings: target.scheduledRecipe.servings,
                    reason: "pantry-friendly",
                  },
                ],
              },
            },
          },
        };
        return pushNotification(
          out,
          "pantry",
          `${item.name} marked spoiled — re-optimisation suggested (1 future slot affected)`,
        );
      }
    }
    return pushNotification(out, "pantry", `${item.name} marked spoiled`);
  });
}

/* ---- nutrition: intake ------------------------------------------------------------------------
 * Production DTO shapes throughout (design/frontend/pages/nutrition.md).
 * Slot transitions are one-way: PENDING → CONFIRMED | OVERRIDDEN | EDITED |
 * SKIPPED, never backwards. The single mock-only exception is repairing an
 * OVERRIDDEN slot whose AI parse failed — see editSlot.
 */

/** Mock clock: every write stamps the fixed "today" evening. */
function nowIso(): string {
  return `${MOCK_TODAY_ISO}T18:05:00Z`;
}

function findIntakeSlot(
  s: StoreState,
  date: string,
  mealSlot: MealSlot,
): IntakeSlotDto | undefined {
  return s.nutrition.intakeDays[date]?.slots.find(
    (sl) => sl.mealSlot === mealSlot,
  );
}

/** Replace one slot of one intake day; bumps the day's version. */
function withIntakeSlot(
  s: StoreState,
  date: string,
  mealSlot: MealSlot,
  fn: (sl: IntakeSlotDto) => IntakeSlotDto,
): StoreState {
  const dayRec = s.nutrition.intakeDays[date];
  if (!dayRec) return s;
  return {
    ...s,
    nutrition: {
      ...s.nutrition,
      intakeDays: {
        ...s.nutrition.intakeDays,
        [date]: {
          ...dayRec,
          slots: dayRec.slots.map((sl) =>
            sl.mealSlot === mealSlot ? fn(sl) : sl,
          ),
          version: dayRec.version + 1,
        },
      },
    },
  };
}

/** Confirm credits the planned values one-to-one (only from PENDING). */
function confirmSlotIn(
  s: StoreState,
  date: string,
  mealSlot: MealSlot,
): StoreState {
  const slot = findIntakeSlot(s, date, mealSlot);
  if (!slot || slot.actual.status !== "PENDING") return s; // one-way
  return withIntakeSlot(s, date, mealSlot, (sl) => ({
    ...sl,
    actual: {
      status: "CONFIRMED",
      calories: sl.planned.calories ?? 0,
      proteinG: sl.planned.proteinG ?? 0,
      carbsG: sl.planned.carbsG ?? 0,
      fatG: sl.planned.fatG ?? 0,
      fibreG: sl.planned.fibreG ?? 0,
      micros: sl.planned.micros ?? {},
      needsAiParse: false,
    },
  }));
}

export function confirmSlot(date: string, mealSlot: MealSlot): void {
  mutate((s) => confirmSlotIn(s, date, mealSlot));
}

function hashText(text: string): number {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0;
  return Math.abs(h);
}

/** Deterministic fake AI parse: plausible values derived from the text hash. */
function fakeParse(text: string): IntakeEntryDto {
  const h = hashText(text);
  return {
    calories: 350 + (h % 401),
    proteinG: 12 + ((h >> 3) % 34),
    carbsG: 25 + ((h >> 5) % 61),
    fatG: 8 + ((h >> 7) % 28),
    fibreG: 2 + ((h >> 9) % 9),
    micros: {},
  };
}

/** Mock parse-failure trigger: "??" anywhere, or fewer than 6 characters. */
function parseWouldFail(text: string): boolean {
  return text.includes("??") || text.trim().length < 6;
}

function finishOverrideParse(
  date: string,
  mealSlot: MealSlot,
  slotId: string,
  text: string,
): void {
  mutate((s) => {
    const slot = findIntakeSlot(s, date, mealSlot);
    if (!slot || slot.id !== slotId || slot.actual.status !== "OVERRIDDEN") {
      return s;
    }
    const failed = parseWouldFail(text);
    let out = withIntakeSlot(s, date, mealSlot, (sl) => ({
      ...sl,
      actual: failed
        ? {
            ...sl.actual,
            calories: 0,
            proteinG: 0,
            carbsG: 0,
            fatG: 0,
            fibreG: 0,
            needsAiParse: true,
          }
        : { ...sl.actual, ...fakeParse(text), needsAiParse: false },
    }));
    out = {
      ...out,
      nutrition: {
        ...out.nutrition,
        parsingSlotIds: out.nutrition.parsingSlotIds.filter(
          (id) => id !== slotId,
        ),
      },
    };
    return failed
      ? pushNotification(
          out,
          "ai",
          `Couldn't read your ${mealSlot.toLowerCase()} note — enter values manually`,
        )
      : out;
  });
}

/**
 * "Log what I ate": free-text override (POST …/override). The slot flips to
 * OVERRIDDEN immediately; ~0.8 s later the fake AI parse either fills
 * structured actuals or sets needsAiParse (zero values + repair banner).
 */
export function overrideSlot(
  date: string,
  mealSlot: MealSlot,
  freeText: string,
): void {
  const text = freeText.trim().slice(0, 512);
  if (!text) return;
  let slotId: string | undefined;
  mutate((s) => {
    const slot = findIntakeSlot(s, date, mealSlot);
    if (!slot || slot.actual.status !== "PENDING") return s; // one-way
    slotId = slot.id;
    const out = withIntakeSlot(s, date, mealSlot, (sl) => ({
      ...sl,
      actual: {
        status: "OVERRIDDEN",
        overrideFreeText: text,
        overriddenAt: nowIso(),
        micros: {},
        needsAiParse: false,
      },
    }));
    return {
      ...out,
      nutrition: {
        ...out.nutrition,
        parsingSlotIds: [...out.nutrition.parsingSlotIds, slot.id],
      },
    };
  });
  if (slotId !== undefined) {
    const id = slotId;
    setTimeout(() => finishOverrideParse(date, mealSlot, id, text), 800);
  }
}

/**
 * Structured edit (POST …/edit). Legal from PENDING. Also allowed — MOCK
 * ONLY — from OVERRIDDEN with needsAiParse: the backend has no repair
 * transition for a failed parse (spec §8 open question 1), but the design
 * review needs the flow; the page footnotes the gap.
 */
export function editSlot(
  date: string,
  mealSlot: MealSlot,
  values: IntakeEntryDto,
): void {
  mutate((s) => {
    const slot = findIntakeSlot(s, date, mealSlot);
    if (!slot) return s;
    const repairable =
      slot.actual.status === "OVERRIDDEN" && slot.actual.needsAiParse;
    if (slot.actual.status !== "PENDING" && !repairable) return s; // one-way
    return withIntakeSlot(s, date, mealSlot, (sl) => ({
      ...sl,
      actual: {
        ...sl.actual,
        status: "EDITED",
        calories: values.calories,
        proteinG: values.proteinG,
        carbsG: values.carbsG,
        fatG: values.fatG,
        fibreG: values.fibreG ?? 0,
        micros: values.micros ?? {},
        needsAiParse: false,
      },
    }));
  });
}

export function skipSlot(date: string, mealSlot: MealSlot): void {
  mutate((s) => {
    const slot = findIntakeSlot(s, date, mealSlot);
    if (!slot || slot.actual.status !== "PENDING") return s; // one-way
    return withIntakeSlot(s, date, mealSlot, (sl) => ({
      ...sl,
      actual: {
        status: "SKIPPED",
        calories: 0,
        proteinG: 0,
        carbsG: 0,
        fatG: 0,
        fibreG: 0,
        micros: {},
        needsAiParse: false,
      },
    }));
  });
}

/* ---- nutrition: snacks --------------------------------------------------------------------- */

let snackSeq = 100;

/** Log a snack (POST …/snacks) — full LogSnackRequest shape. */
export function addSnack(date: string, req: LogSnackRequest): void {
  const text = req.freeText.trim().slice(0, 255);
  if (!text || req.quantityG <= 0) return;
  mutate((s) => {
    const dayRec = s.nutrition.intakeDays[date];
    if (!dayRec) return s;
    const dto: IntakeSnackDto = {
      id: `snack-${++snackSeq}`,
      ingredientMappingKey: req.ingredientMappingKey ?? null,
      freeText: text,
      quantityG: req.quantityG,
      calories: req.calories,
      proteinG: req.proteinG,
      carbsG: req.carbsG,
      fatG: req.fatG,
      fibreG: req.fibreG ?? null,
      micros: req.micros ?? null,
      source: req.source,
      loggedAt: nowIso(),
    };
    const out: StoreState = {
      ...s,
      nutrition: {
        ...s.nutrition,
        intakeDays: {
          ...s.nutrition.intakeDays,
          [date]: {
            ...dayRec,
            snacks: [...dayRec.snacks, dto],
            version: dayRec.version + 1,
          },
        },
      },
    };
    return pushNotification(
      out,
      "ai",
      `Snack logged — ${text.toLowerCase()}, ${req.calories} kcal`,
    );
  });
}

export function removeSnack(date: string, snackId: string): void {
  mutate((s) => {
    const dayRec = s.nutrition.intakeDays[date];
    if (!dayRec || !dayRec.snacks.some((sn) => sn.id === snackId)) return s;
    return {
      ...s,
      nutrition: {
        ...s.nutrition,
        intakeDays: {
          ...s.nutrition.intakeDays,
          [date]: {
            ...dayRec,
            snacks: dayRec.snacks.filter((sn) => sn.id !== snackId),
            version: dayRec.version + 1,
          },
        },
      },
    };
  });
}

/* ---- nutrition: daily activity --------------------------------------------------------------- */

/** PUT targets/activity/{date} — upsert the day's activity level + notes. */
export function upsertActivity(
  date: string,
  activityLevel: ActivityLevel,
  notes?: string | null,
): void {
  mutate((s) => ({
    ...s,
    nutrition: {
      ...s.nutrition,
      dailyActivity: {
        ...s.nutrition.dailyActivity,
        [date]: {
          id: `act-${date}`,
          userId: MOCK_USER_ID,
          onDate: date,
          activityLevel,
          notes: notes?.trim() ? notes.trim().slice(0, 255) : null,
          createdAt: s.nutrition.dailyActivity[date]?.createdAt ?? nowIso(),
        },
      },
    },
  }));
}

/* ---- nutrition: targets ------------------------------------------------------------------------ */

/**
 * Full-replacement PUT /nutrition/targets. Bumps the version and records any
 * direction changes in userOverriddenDirections ("custom" badges).
 *
 * Mock note: no 409 simulation — the real endpoint rejects the save when
 * req.expectedVersion != current version (spec §4 conflict card); here
 * expectedVersion is accepted as-is.
 */
export function saveTargets(req: UpdateTargetsRequest): void {
  mutate((s) => {
    const macroKeys = ["protein", "carbs", "fat", "fibre", "satFat"] as const;
    const overridden = new Set(s.targets.userOverriddenDirections);
    for (const k of macroKeys) {
      if (req[k].direction !== s.targets[k].direction) overridden.add(k);
    }
    const version = s.targets.version + 1;
    const next: TargetsDto = {
      ...s.targets,
      goal: req.goal,
      calories: req.calories,
      protein: req.protein,
      carbs: req.carbs,
      fat: req.fat,
      fibre: req.fibre,
      satFat: req.satFat,
      notes: req.notes ?? null,
      perMealDistribution: req.perMealDistribution,
      microTargets: req.microTargets,
      eatingWindow: req.eatingWindow ?? null,
      activityAdjustments: req.activityAdjustments,
      userOverriddenDirections: [...overridden],
      version,
    };
    return pushNotification(
      { ...s, targets: next },
      "ai",
      `Nutrition targets saved — v${version}`,
    );
  });
}

/* ---- nutrition: journal -------------------------------------------------------------------------- */

let journalSeq = 10;

export function addJournalEntry(
  onDate: string,
  mealSlot: MealSlot | null,
  text: string,
): void {
  const trimmed = text.trim().slice(0, 4000);
  if (!trimmed) return;
  mutate((s) => ({
    ...s,
    nutrition: {
      ...s.nutrition,
      journal: [
        {
          id: `jm-${++journalSeq}`,
          userId: MOCK_USER_ID,
          onDate,
          mealSlot,
          journalEntry: trimmed,
          loggedAt: nowIso(),
          optimisticVersion: 0,
        },
        ...s.nutrition.journal,
      ],
    },
  }));
}

/** PUT …/entries/{id} — bumps optimisticVersion (no 409 simulation). */
export function updateJournalEntry(
  id: string,
  text: string,
  mealSlot: MealSlot | null,
): void {
  const trimmed = text.trim().slice(0, 4000);
  if (!trimmed) return;
  mutate((s) => ({
    ...s,
    nutrition: {
      ...s.nutrition,
      journal: s.nutrition.journal.map((e) =>
        e.id === id
          ? {
              ...e,
              journalEntry: trimmed,
              mealSlot,
              optimisticVersion: e.optimisticVersion + 1,
            }
          : e,
      ),
    },
  }));
}

export function deleteJournalEntry(id: string): void {
  mutate((s) => ({
    ...s,
    nutrition: {
      ...s.nutrition,
      journal: s.nutrition.journal.filter((e) => e.id !== id),
    },
  }));
}

/* ---- nutrition: health directives ------------------------------------------------------------------ */

function directiveTypeLabel(t: string): string {
  return t.toLowerCase().replace(/_/g, " ");
}

/**
 * Accept a directive (never auto-applied — explicit user decision). Blocked
 * verdicts cannot be accepted. An accepted TARGET_ADJUSTMENT also edits the
 * targets server-side (actorKind HEALTH_DIRECTIVE) — mirrored here for the
 * seeded whoop directive: training-day surplus +200 → +150.
 */
export function acceptDirective(
  id: string,
  userModification?: DirectiveUserModification,
): void {
  mutate((s) => {
    const d = s.nutrition.directives.find((x) => x.id === id);
    if (
      !d ||
      d.status !== "PENDING_REVIEW" ||
      d.safetyGateVerdict === "BLOCKED"
    ) {
      return s;
    }
    let out: StoreState = {
      ...s,
      nutrition: {
        ...s.nutrition,
        directives: s.nutrition.directives.map((x) =>
          x.id === id
            ? {
                ...x,
                status: "ACCEPTED" as const,
                decidedAt: nowIso(),
                decidedByUserId: MOCK_USER_ID,
                userModification: userModification ?? null,
                optimisticVersion: x.optimisticVersion + 1,
              }
            : x,
        ),
      },
    };
    if (d.directiveType === "TARGET_ADJUSTMENT") {
      out = {
        ...out,
        targets: {
          ...out.targets,
          activityAdjustments: out.targets.activityAdjustments.map((a) =>
            a.activityLevel === "TRAINING_DAY"
              ? { ...a, calorieModifier: 150 }
              : a,
          ),
          version: out.targets.version + 1,
        },
      };
    }
    return pushNotification(
      out,
      "ai",
      `Health directive accepted — ${d.sourcePlatform} ${directiveTypeLabel(
        d.directiveType,
      )}${userModification ? " (with your modification)" : ""}`,
    );
  });
}

export function rejectDirective(id: string, reason?: string): void {
  mutate((s) => {
    const d = s.nutrition.directives.find((x) => x.id === id);
    if (!d || d.status !== "PENDING_REVIEW") return s;
    const out: StoreState = {
      ...s,
      nutrition: {
        ...s.nutrition,
        directives: s.nutrition.directives.map((x) =>
          x.id === id
            ? {
                ...x,
                status: "REJECTED" as const,
                decidedAt: nowIso(),
                decidedByUserId: MOCK_USER_ID,
                rejectionReason: reason?.trim()
                  ? reason.trim().slice(0, 255)
                  : null,
                optimisticVersion: x.optimisticVersion + 1,
              }
            : x,
        ),
      },
    };
    return pushNotification(
      out,
      "ai",
      `Health directive rejected — ${d.sourcePlatform} ${directiveTypeLabel(
        d.directiveType,
      )}`,
    );
  });
}

/* ---- nutrition: ingredient data quality ----------------------------------------------------------------- */

/**
 * PUT /nutrition/ingredients/{searchTerm}/correction — the row flips to
 * MANUAL / confidence 1.0 and leaves the needs-review queue.
 */
export function correctIngredient(
  searchTerm: string,
  override: IngredientNutritionDocument,
): void {
  mutate((s) => {
    const row = s.nutrition.ingredientCache.find(
      (r) => r.searchTerm === searchTerm,
    );
    if (!row) return s;
    const out: StoreState = {
      ...s,
      nutrition: {
        ...s.nutrition,
        ingredientCache: s.nutrition.ingredientCache.map((r) =>
          r.searchTerm === searchTerm
            ? {
                ...r,
                nutritionPer100g: override,
                source: "MANUAL" as const,
                confidence: 1.0,
                needsReview: false,
                lastVerifiedAt: nowIso(),
                version: r.version + 1,
              }
            : r,
        ),
      },
    };
    return pushNotification(
      out,
      "ai",
      `Ingredient corrected — ${searchTerm} verified manually`,
    );
  });
}

/** Cache-only search (POST /ingredients/search with cacheOnly=true in v1). */
export function searchIngredients(
  cache: IngredientNutritionDto[],
  query: string,
  maxResults = 8,
): IngredientNutritionDto[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  return cache
    .filter((r) => r.searchTerm.toLowerCase().includes(q))
    .slice(0, maxResults);
}

/* ---- nutrition: computed aggregates -----------------------------------------------------------------------
 * Pure functions, not useStore selectors (they build fresh objects). Pages
 * call them during render from stored slices.
 */

function addMicros(
  into: Record<string, number>,
  m?: Record<string, number> | null,
): void {
  if (!m) return;
  for (const [k, v] of Object.entries(m)) {
    into[k] = Math.round(((into[k] ?? 0) + v) * 100) / 100;
  }
}

const round1 = (n: number): number => Math.round(n * 10) / 10;

/**
 * GET intake/{date}/aggregate equivalent. "Remaining" is target-based
 * (vs TargetsDto), matching the backend's daily-aggregate endpoint.
 *
 * Backend gap (flagged in the spec PR): DailyAggregateDto has no satFat
 * aggregate although TargetsDto carries a satFat target — the stat band's
 * sixth cell reads microsActualSoFar["saturated_fat_g"] instead.
 */
export function computeDailyAggregate(
  day: IntakeDayDto | undefined,
  targets: TargetsDto,
): DailyAggregateDto {
  let caloriesPlanned = 0;
  let caloriesActual = 0;
  const planned = { protein: 0, carbs: 0, fat: 0, fibre: 0 };
  const actual = { protein: 0, carbs: 0, fat: 0, fibre: 0 };
  const microsActual: Record<string, number> = {};
  if (day) {
    for (const sl of day.slots) {
      caloriesPlanned += sl.planned.calories ?? 0;
      planned.protein += sl.planned.proteinG ?? 0;
      planned.carbs += sl.planned.carbsG ?? 0;
      planned.fat += sl.planned.fatG ?? 0;
      planned.fibre += sl.planned.fibreG ?? 0;
      if (sl.actual.status !== "PENDING") {
        caloriesActual += sl.actual.calories ?? 0;
        actual.protein += sl.actual.proteinG ?? 0;
        actual.carbs += sl.actual.carbsG ?? 0;
        actual.fat += sl.actual.fatG ?? 0;
        actual.fibre += sl.actual.fibreG ?? 0;
        addMicros(microsActual, sl.actual.micros);
      }
    }
    for (const sn of day.snacks) {
      caloriesActual += sn.calories;
      actual.protein += sn.proteinG;
      actual.carbs += sn.carbsG;
      actual.fat += sn.fatG;
      actual.fibre += sn.fibreG ?? 0;
      addMicros(microsActual, sn.micros);
    }
  }
  const remaining = (target: number, got: number): number =>
    Math.max(0, round1(target - got));
  const macro = (key: keyof typeof planned, targetG: number | null | undefined) => ({
    plannedG: round1(planned[key]),
    actualSoFarG: round1(actual[key]),
    remainingG: remaining(targetG ?? 0, actual[key]),
  });
  return {
    caloriesPlanned,
    caloriesActualSoFar: caloriesActual,
    caloriesRemaining: remaining(targets.calories.dailyTarget, caloriesActual),
    protein: macro("protein", targets.protein.targetG),
    carbs: macro("carbs", targets.carbs.targetG),
    fat: macro("fat", targets.fat.targetG),
    fibre: macro("fibre", targets.fibre.targetG),
    microsActualSoFar: microsActual,
  };
}

type FloorMacroKey = "protein" | "carbs" | "fat" | "fibre";
const FLOOR_MACROS: FloorMacroKey[] = ["protein", "carbs", "fat", "fibre"];

/**
 * Day indices (0 = Mon) on which `key`'s hard floor was missed — past days
 * only (today is still in flight). The mock checks per-day hard floors
 * (isHardFloor + floorG + a lower bound); the contract's floorViolations is
 * key-only, so the page derives the day annotation from this helper.
 */
export function floorViolationDayIndices(
  n: NutritionState,
  targets: TargetsDto,
  key: string,
): number[] {
  const macroKey = FLOOR_MACROS.find((k) => k === key);
  if (!macroKey) return [];
  const t = targets[macroKey];
  if (!t.isHardFloor || t.floorG == null || t.direction === "UPPER_LIMIT") {
    return [];
  }
  const out: number[] = [];
  WEEK_DATES.forEach((date, i) => {
    if (date >= MOCK_TODAY_ISO) return;
    const agg = computeDailyAggregate(n.intakeDays[date], targets);
    if (agg[macroKey].actualSoFarG < (t.floorG ?? 0)) out.push(i);
  });
  return out;
}

/**
 * GET intake/week/{weekStart}/aggregate equivalent — Mon-anchored. Past days
 * are settled, today is live, future days aggregate to zero actuals.
 */
export function computeWeeklyAggregate(
  n: NutritionState,
  targets: TargetsDto,
): WeeklyAggregateDto {
  const perDay = WEEK_DATES.map((date) =>
    computeDailyAggregate(n.intakeDays[date], targets),
  );
  const sum = (pick: (d: DailyAggregateDto) => number): number =>
    round1(perDay.reduce((acc, d) => acc + pick(d), 0));
  const totalMicros: Record<string, number> = {};
  for (const d of perDay) addMicros(totalMicros, d.microsActualSoFar);
  const weeklyTotal: DailyAggregateDto = {
    caloriesPlanned: sum((d) => d.caloriesPlanned),
    caloriesActualSoFar: sum((d) => d.caloriesActualSoFar),
    caloriesRemaining: Math.max(
      0,
      targets.calories.dailyTarget * 7 - sum((d) => d.caloriesActualSoFar),
    ),
    protein: {
      plannedG: sum((d) => d.protein.plannedG),
      actualSoFarG: sum((d) => d.protein.actualSoFarG),
      remainingG: 0,
    },
    carbs: {
      plannedG: sum((d) => d.carbs.plannedG),
      actualSoFarG: sum((d) => d.carbs.actualSoFarG),
      remainingG: 0,
    },
    fat: {
      plannedG: sum((d) => d.fat.plannedG),
      actualSoFarG: sum((d) => d.fat.actualSoFarG),
      remainingG: 0,
    },
    fibre: {
      plannedG: sum((d) => d.fibre.plannedG),
      actualSoFarG: sum((d) => d.fibre.actualSoFarG),
      remainingG: 0,
    },
    microsActualSoFar: totalMicros,
  };
  // Key-only list per the contract (the page derives day chips via
  // floorViolationDayIndices). Hard-floor macros only — the planner's
  // multiplicative gate; micro hard floors are possible in the contract but
  // not simulated here.
  const floorViolations = FLOOR_MACROS.filter(
    (k) => floorViolationDayIndices(n, targets, k).length > 0,
  );
  return {
    weekStart: WEEK_DATES[0],
    weekEnd: WEEK_DATES[WEEK_DATES.length - 1],
    perDay,
    weeklyTotal,
    floorViolations,
  };
}

/**
 * Direction-aware attention tone for a stat cell (spec §3b): LOWER_FLOOR
 * warns when behind pace (<55% of target), UPPER_LIMIT warns when over,
 * BOTH_BOUNDED warns on either side.
 */
export function macroWarn(
  direction: EnforcementDirection,
  actual: number,
  target: number,
): boolean {
  if (target <= 0) return false;
  const behind = actual / target < 0.55;
  const over = actual > target;
  if (direction === "LOWER_FLOOR") return behind;
  if (direction === "UPPER_LIMIT") return over;
  return behind || over;
}

export interface DivergenceSignal {
  /** Macro key with the largest |variance| ≥ 15%. */
  key: string;
  /** Signed percent variance of actual vs planned-so-far. */
  pct: number;
}

/**
 * Divergence advisor condition (spec §3a): any macro |variance| ≥ 15%
 * between planned-so-far (decided slots) and actual-so-far (decided slots +
 * snacks) while at least one slot is still PENDING.
 */
export function computeDivergence(
  day: IntakeDayDto | undefined,
): DivergenceSignal | null {
  if (!day || !day.slots.some((sl) => sl.actual.status === "PENDING")) {
    return null;
  }
  const planned = { calories: 0, protein: 0, carbs: 0, fat: 0, fibre: 0 };
  const actual = { calories: 0, protein: 0, carbs: 0, fat: 0, fibre: 0 };
  for (const sl of day.slots) {
    if (sl.actual.status === "PENDING") continue;
    planned.calories += sl.planned.calories ?? 0;
    planned.protein += sl.planned.proteinG ?? 0;
    planned.carbs += sl.planned.carbsG ?? 0;
    planned.fat += sl.planned.fatG ?? 0;
    planned.fibre += sl.planned.fibreG ?? 0;
    actual.calories += sl.actual.calories ?? 0;
    actual.protein += sl.actual.proteinG ?? 0;
    actual.carbs += sl.actual.carbsG ?? 0;
    actual.fat += sl.actual.fatG ?? 0;
    actual.fibre += sl.actual.fibreG ?? 0;
  }
  for (const sn of day.snacks) {
    actual.calories += sn.calories;
    actual.protein += sn.proteinG;
    actual.carbs += sn.carbsG;
    actual.fat += sn.fatG;
    actual.fibre += sn.fibreG ?? 0;
  }
  let worst: DivergenceSignal | null = null;
  for (const key of Object.keys(planned) as Array<keyof typeof planned>) {
    if (planned[key] <= 0) continue;
    const pct = ((actual[key] - planned[key]) / planned[key]) * 100;
    if (Math.abs(pct) >= 15 && (!worst || Math.abs(pct) > Math.abs(worst.pct))) {
      worst = { key, pct };
    }
  }
  return worst;
}

/* ---- notifications ---------------------------------------------------------------------------- */

export function addNotification(kind: NotificationKind, title: string): void {
  mutate((s) => pushNotification(s, kind, title));
}

export function markNotificationRead(id: string): void {
  mutate((s) => ({
    ...s,
    notifications: s.notifications.map((n) =>
      n.id === id ? { ...n, read: true } : n,
    ),
  }));
}

export function markAllNotificationsRead(): void {
  mutate((s) => ({
    ...s,
    notifications: s.notifications.map((n) =>
      n.read ? n : { ...n, read: true },
    ),
  }));
}

export function dismissNotification(id: string): void {
  mutate((s) => ({
    ...s,
    notifications: s.notifications.filter((n) => n.id !== id),
  }));
}

export function toggleMutedKind(kind: NotificationKind): void {
  mutate((s) => ({
    ...s,
    notificationPrefs: {
      ...s.notificationPrefs,
      muted: s.notificationPrefs.muted.includes(kind)
        ? s.notificationPrefs.muted.filter((k) => k !== kind)
        : [...s.notificationPrefs.muted, kind],
    },
  }));
}

export function setQuietHours(start: string, end: string): void {
  mutate((s) => ({
    ...s,
    notificationPrefs: {
      ...s.notificationPrefs,
      quietStart: start,
      quietEnd: end,
    },
  }));
}

/* ---- preferences ---------------------------------------------------------------------------------- */

/** Fake async taste-profile refresh: ~1s, then version bump + notification. */
export function refreshTasteProfile(): void {
  if (state.preferences.refreshing) return;
  mutate((s) => ({
    ...s,
    preferences: { ...s.preferences, refreshing: true },
  }));
  setTimeout(() => {
    mutate((s) => {
      if (!s.preferences.refreshing) return s;
      const version = s.preferences.profileVersion + 1;
      return pushNotification(
        {
          ...s,
          preferences: {
            ...s.preferences,
            refreshing: false,
            profileVersion: version,
          },
        },
        "ai",
        `Taste profile refreshed — v${version} built from 3 new feedback signals`,
      );
    });
  }, 1000);
}

export function rollbackTasteProfile(): void {
  mutate((s) => {
    if (s.preferences.refreshing || s.preferences.profileVersion <= 3) return s;
    const version = s.preferences.profileVersion - 1;
    return pushNotification(
      { ...s, preferences: { ...s.preferences, profileVersion: version } },
      "ai",
      `Taste profile rolled back to v${version}`,
    );
  });
}

/**
 * Remove a hard constraint. The GAP-04 interstitial (type-to-confirm) lives
 * in the Preferences page — this action runs only after that confirmation.
 */
export function removeConstraint(kind: ConstraintKind, name: string): void {
  mutate((s) => {
    const list =
      kind === "allergy" ? s.preferences.allergies : s.preferences.dietary;
    if (!list.includes(name)) return s;
    const next = list.filter((c) => c !== name);
    return pushNotification(
      {
        ...s,
        preferences: {
          ...s.preferences,
          allergies: kind === "allergy" ? next : s.preferences.allergies,
          dietary: kind === "dietary" ? next : s.preferences.dietary,
        },
      },
      "ai",
      `Safety filter updated — ${name.toLowerCase()} removed from ${
        kind === "allergy" ? "allergies" : "dietary identities"
      }`,
    );
  });
}

export function addAllergy(name: string): void {
  const trimmed = name.trim();
  if (!trimmed) return;
  mutate((s) => {
    if (
      s.preferences.allergies.some(
        (a) => a.toLowerCase() === trimmed.toLowerCase(),
      )
    ) {
      return s;
    }
    return {
      ...s,
      preferences: {
        ...s.preferences,
        allergies: [...s.preferences.allergies, trimmed],
      },
    };
  });
}

function formatTime(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

/**
 * Nudge a lifestyle slot time ±15 min. Today's timeline does NOT mirror it:
 * per the serve-time contract, MealSlotDto.mealTime is the raw nullable
 * override and the lifestyle-config fallback resolution is server-internal
 * with no HTTP exposure (plan.md §8 Q3).
 */
export function adjustSlotTime(slot: MealSlotKey, direction: 1 | -1): void {
  mutate((s) => {
    const [h, m] = s.preferences.lifestyle.slotTimes[slot]
      .split(":")
      .map(Number);
    const next = Math.max(
      5 * 60,
      Math.min(23 * 60, h * 60 + m + direction * 15),
    );
    const time = formatTime(next);
    return {
      ...s,
      preferences: {
        ...s.preferences,
        lifestyle: {
          ...s.preferences.lifestyle,
          slotTimes: { ...s.preferences.lifestyle.slotTimes, [slot]: time },
        },
      },
    };
  });
}

export function adjustPortionScale(direction: 1 | -1): void {
  mutate((s) => {
    const next =
      Math.round(
        Math.max(
          0.5,
          Math.min(2, s.preferences.lifestyle.portionScale + direction * 0.1),
        ) * 10,
      ) / 10;
    return {
      ...s,
      preferences: {
        ...s.preferences,
        lifestyle: { ...s.preferences.lifestyle, portionScale: next },
      },
    };
  });
}

/** Projected basket total used for the grocery headroom maths (mock-fixed). */
const PROJECTED_BASKET = 47.3;

/** Nudge the weekly budget ±£5 — pantry budget + grocery headroom follow. */
export function adjustWeeklyBudget(direction: 1 | -1): void {
  mutate((s) => {
    const budget = Math.max(
      25,
      Math.min(120, s.preferences.lifestyle.weeklyBudget + direction * 5),
    );
    if (budget === s.preferences.lifestyle.weeklyBudget) return s;
    const headroom = budget - PROJECTED_BASKET;
    return {
      ...s,
      preferences: {
        ...s.preferences,
        lifestyle: { ...s.preferences.lifestyle, weeklyBudget: budget },
      },
      pantry: {
        ...s.pantry,
        budget: { ...s.pantry.budget, total: budget },
      },
      grocery: {
        ...s.grocery,
        headroom: `${headroom < 0 ? "−" : ""}£${Math.abs(headroom).toFixed(2)}`,
        headroomSub: `vs £${budget} weekly`,
      },
    };
  });
}

/* ---- activity / feedback ----------------------------------------------------------------------------- */

/** Confidence tiers: ≥0.8 routed · 0.5–0.8 check me · <0.5 needs you. */
export function tierFor(conf: number): ConfidenceTier {
  if (conf >= 0.8) return "high";
  if (conf >= 0.5) return "mid";
  return "low";
}

let feedbackSeq = 10;

/** The canned 3-route fixture from the D6 mockup ("salty"/"portion" texts). */
function cannedRoutes(): FeedbackRoute[] {
  return [
    {
      dest: "Recipe",
      conf: 0.92,
      action:
        "The recipe optimiser will propose a lower-salt version of chicken stir-fry.",
    },
    {
      dest: "Nutrition",
      conf: 0.71,
      action:
        "Increase per-meal portion targets for dinners — I think this is what you meant.",
    },
    {
      dest: "Preference",
      conf: 0.44,
      question:
        "Is “too salty” about this one dish, or do you generally prefer less salt?",
      options: ["Just this dish", "Generally less salt", "Skip"],
    },
  ];
}

/**
 * Classify a feedback text (mock): "salty"/"portion" hits the canned
 * 3-route fixture; anything else routes to Preference at 0.85. Returns the
 * new entry id so the modal can track it. Low-confidence routes also land
 * a clarification (id `c-<entryId>`) in the Activity inbox.
 */
export function submitFeedback(text: string): string {
  const id = `f${++feedbackSeq}`;
  const routes: FeedbackRoute[] = /salty|salt|portion/i.test(text)
    ? cannedRoutes()
    : [
        {
          dest: "Preference",
          conf: 0.85,
          action:
            "Noted as a general preference — your taste profile weighs this from the next plan.",
        },
      ];
  mutate((s) => {
    const entry: FeedbackEntry = { id, when: "Just now", text, routes };
    const lowRoute = routes.find((r) => tierFor(r.conf) === "low");
    let out: StoreState = {
      ...s,
      activity: {
        ...s.activity,
        feedback: [entry, ...s.activity.feedback],
        clarifications: lowRoute?.question
          ? [
              {
                id: `c-${id}`,
                question: lowRoute.question,
                options: lowRoute.options ?? [],
                context: text,
              },
              ...s.activity.clarifications,
            ]
          : s.activity.clarifications,
      },
    };
    out = pushNotification(
      out,
      "ai",
      `Feedback routed to ${routes.length} destination${
        routes.length === 1 ? "" : "s"
      }${lowRoute ? " — one question needs you" : ""}`,
    );
    return out;
  });
  return id;
}

/** "This isn't right" — flags the routing as corrected, teaches the mock. */
export function markFeedbackCorrected(entryId: string): void {
  mutate((s) => {
    const entry = s.activity.feedback.find((f) => f.id === entryId);
    if (!entry || entry.corrected) return s;
    return pushNotification(
      {
        ...s,
        activity: {
          ...s.activity,
          feedback: s.activity.feedback.map((f) =>
            f.id === entryId ? { ...f, corrected: true } : f,
          ),
        },
      },
      "ai",
      "Routing correction recorded — the classifier learns from this",
    );
  });
}

/**
 * Answer a clarification: resolves the inbox card, marks the originating
 * route answered, and (unless skipped) adds a routed history entry.
 */
export function answerClarification(id: string, option: string): void {
  mutate((s) => {
    const clar = s.activity.clarifications.find((c) => c.id === id);
    if (!clar) return s;
    const skipped = option === "Skip";
    const feedback = s.activity.feedback.map((f) => ({
      ...f,
      routes: f.routes.map((r) =>
        r.question === clar.question && !r.answered
          ? { ...r, answered: option }
          : r,
      ),
    }));
    const answeredEntry: FeedbackEntry = {
      id: `f${++feedbackSeq}`,
      when: "Just now",
      text: option,
      routes: [
        {
          dest: "Preference",
          conf: 0.97,
          action: "Clarification answered — applied to your taste profile.",
        },
      ],
    };
    const out: StoreState = {
      ...s,
      activity: {
        ...s.activity,
        clarifications: s.activity.clarifications.filter((c) => c.id !== id),
        feedback: skipped ? feedback : [answeredEntry, ...feedback],
      },
    };
    return skipped
      ? out
      : pushNotification(
          out,
          "ai",
          `Clarification answered — “${option.toLowerCase()}” routed to Preference`,
        );
  });
}

/* ---- household ------------------------------------------------------------------------------------------ */

export function renameHousehold(name: string): void {
  const trimmed = name.trim();
  if (!trimmed) return;
  mutate((s) => ({ ...s, household: { ...s.household, name: trimmed } }));
}

export function inviteMember(email: string): void {
  const trimmed = email.trim();
  if (!trimmed) return;
  mutate((s) => {
    if (s.household.invites.some((i) => i.email === trimmed)) return s;
    return pushNotification(
      {
        ...s,
        household: {
          ...s.household,
          invites: [
            ...s.household.invites,
            { email: trimmed, sent: "Sent just now" },
          ],
        },
      },
      "ai",
      `Invite sent to ${trimmed}`,
    );
  });
}

export function revokeInvite(email: string): void {
  mutate((s) => ({
    ...s,
    household: {
      ...s.household,
      invites: s.household.invites.filter((i) => i.email !== email),
    },
  }));
}

export function toggleSlotShared(dayType: string, slot: MealSlotKey): void {
  mutate((s) => ({
    ...s,
    household: {
      ...s.household,
      slotConfig: s.household.slotConfig.map((d) =>
        d.dayType !== dayType
          ? d
          : {
              ...d,
              slots: d.slots.map((sl) =>
                sl.slot === slot ? { ...sl, shared: !sl.shared } : sl,
              ),
            },
      ),
    },
  }));
}

/** Fake password change — succeeds with a notification, nothing stored. */
export function changePassword(): void {
  mutate((s) => pushNotification(s, "ai", "Password updated"));
}

/* ---- discovery (discover.md) --------------------------------------------------------------
 * Contract lifecycle: QUEUED → RUNNING → SUCCEEDED | FAILED | PARTIAL.
 * Scrape-log rows are written eagerly per fetch (the live progress feed);
 * SUCCESS rows persist the recipe into the SYSTEM catalogue immediately —
 * "Keep" is then just POST /recipes/{id}/promote (the traced handoff, §5).
 */

let discoveryJobSeq = 0;

function findJob(s: StoreState, jobId: string): DiscoveryJobDto | undefined {
  return s.discovery.jobs.find((j) => j.id === jobId);
}

function replaceJob(s: StoreState, next: DiscoveryJobDto): StoreState {
  return {
    ...s,
    discovery: {
      ...s.discovery,
      jobs: s.discovery.jobs.map((j) => (j.id === next.id ? next : j)),
    },
  };
}

export function openDiscoveryJob(jobId: string | null): void {
  mutate((s) => ({ ...s, discovery: { ...s.discovery, openJobId: jobId } }));
}

/** Skip = local dismissal only; the recipe stays in the system catalogue and
 *  the planner may still draw on it (no contract call — §9 Q5). */
export function skipDiscoveryRow(rowId: string): void {
  mutate((s) =>
    s.discovery.skippedRowIds.includes(rowId)
      ? s
      : {
          ...s,
          discovery: {
            ...s.discovery,
            skippedRowIds: [...s.discovery.skippedRowIds, rowId],
          },
        },
  );
}

/** True while the user has a live (QUEUED/RUNNING) job — the start button
 *  disables (UI rule; the contract itself allows concurrency). */
export function hasLiveDiscoveryJob(s: StoreState): boolean {
  return s.discovery.jobs.some(
    (j) => j.status === "QUEUED" || j.status === "RUNNING",
  );
}

function finalizeJob(
  s: StoreState,
  jobId: string,
  status: DiscoveryJobDto["status"],
  errorSummary: string | null,
): StoreState {
  const job = findJob(s, jobId);
  if (!job) return s;
  const rows = s.discovery.scrapeLog[jobId] ?? [];
  const bySource = new Map<string, { ok: number; err: number }>();
  for (const row of rows) {
    const slot = bySource.get(row.sourceKey) ?? { ok: 0, err: 0 };
    if (row.status === "HTTP_ERROR") slot.err += 1;
    else slot.ok += 1;
    bySource.set(row.sourceKey, slot);
  }
  const sourcesSucceeded = job.sourcesRequested.filter(
    (k) => (bySource.get(k)?.ok ?? 0) > 0,
  );
  const sourcesFailed = job.sourcesRequested.filter((k) => {
    const slot = bySource.get(k);
    return slot != null && slot.ok === 0 && slot.err > 0;
  });
  const replaced = replaceJob(s, {
    ...job,
    status,
    completedAt: nowIso(),
    sourcesSucceeded,
    sourcesFailed,
    errorSummary,
    optimisticVersion: job.optimisticVersion + 1,
  });
  return {
    ...replaced,
    discovery: { ...replaced.discovery, cancelRequested: null },
  };
}

function runDiscoveryStep(jobId: string, scriptIndex: number): void {
  const s = state;
  const job = findJob(s, jobId);
  if (!job || job.status !== "RUNNING") return;

  // RUNNING cancel honoured between candidates (in-memory flag, §4).
  if (s.discovery.cancelRequested === jobId) {
    mutate((st) =>
      pushNotification(
        finalizeJob(st, jobId, "FAILED", "cancelled by user"),
        "ai",
        `Discovery cancelled — ${findJob(st, jobId)?.recipesIngested ?? 0} already-saved recipes kept`,
      ),
    );
    return;
  }

  const allowed = new Set(job.sourcesRequested);
  const script = DISCOVERY_RUN_SCRIPT.filter((f) => allowed.has(f.sourceKey));
  if (scriptIndex >= script.length) {
    mutate((st) => {
      const j = findJob(st, jobId);
      const out = finalizeJob(st, jobId, "SUCCEEDED", null);
      return pushNotification(
        out,
        "ai",
        `Discovery finished — ${j?.recipesIngested ?? 0} recipes saved to the pool`,
      );
    });
    return;
  }

  const fetch = script[scriptIndex];
  mutate((st) => {
    const j = findJob(st, jobId);
    if (!j || j.status !== "RUNNING") return st;
    const quotaMet = j.recipesIngested >= j.requestedCount;
    let next = st;
    let recipeId: string | null = null;
    let effective = fetch;
    if (fetch.status === "SUCCESS" && fetch.recipe) {
      if (quotaMet) {
        effective = { ...fetch, status: "SKIPPED", skipReason: "JOB_QUOTA_REACHED", recipe: undefined };
      } else {
        recipeId = findRecipe(st, fetch.recipe.id)
          ? `${fetch.recipe.id}-${jobId}`
          : fetch.recipe.id;
        const built = buildRecipe({ ...fetch.recipe, id: recipeId, createdAt: nowIso() });
        next = {
          ...st,
          recipes: [built.dto, ...st.recipes],
          recipeData: {
            ...st.recipeData,
            versions: { ...st.recipeData.versions, [recipeId]: built.versions },
            provenance: {
              ...st.recipeData.provenance,
              [recipeId]: {
                id: `imp-${recipeId}`,
                recipeId,
                sourceType: "WEB_DISCOVERED",
                sourceUrl: fetch.url,
                sourcePayload: null,
                extractionMethod: fetch.method ?? null,
                duplicateOfRecipeId: null,
                importedAt: nowIso(),
                importedByUserId: MOCK_USER_ID,
              },
            },
          },
        };
      }
    }
    const row = rowFromScript(jobId, effective, recipeId, nowIso());
    const counters = {
      candidatesSeen: j.candidatesSeen + 1,
      candidatesAfterFilter:
        j.candidatesAfterFilter + (effective.skipReason === "AI_FILTER_REJECTED" ? 0 : 1),
      recipesIngested: j.recipesIngested + (recipeId ? 1 : 0),
      recipesSkippedDuplicate:
        j.recipesSkippedDuplicate + (effective.status === "DUPLICATE" ? 1 : 0),
    };
    const replaced = replaceJob(next, { ...j, ...counters });
    return {
      ...replaced,
      discovery: {
        ...replaced.discovery,
        scrapeLog: {
          ...replaced.discovery.scrapeLog,
          [jobId]: [...(replaced.discovery.scrapeLog[jobId] ?? []), row],
        },
      },
    };
  });
  setTimeout(() => runDiscoveryStep(jobId, scriptIndex + 1), 700);
}

/**
 * POST /discovery/jobs (202 + QUEUED DTO; the caller polls — no push channel
 * in v1, §9 Q1). Constraints are frozen at enqueue; the hard-constraint
 * snapshot is injected by the CALLER (client-trust hole, §9 Q3).
 */
export function startDiscoveryJob(req: StartDiscoveryJobRequest): void {
  if (hasLiveDiscoveryJob(state)) {
    pushToast("A discovery is already running — wait for it to finish", "warn");
    return;
  }
  const cap = req.constraints.maxRecipesPerSource;
  if (cap != null && cap > req.requestedCount) {
    pushToast("400 — per-source cap can't exceed the total requested", "warn");
    return;
  }
  const enabledKeys = new Set(
    state.discovery.sources.filter((x) => x.enabled).map((x) => x.sourceKey),
  );
  const requested = req.sourceKeys ?? [...enabledKeys];
  const unknown = requested.filter((k) => !enabledKeys.has(k));
  if (requested.length === 0 || unknown.length > 0) {
    pushToast(
      `422 — no enabled source matched${unknown.length > 0 ? ` (${unknown.join(", ")})` : ""}`,
      "warn",
    );
    return;
  }
  const jobId = `djob-run-${++discoveryJobSeq}`;
  const job: DiscoveryJobDto = {
    id: jobId,
    userId: MOCK_USER_ID,
    trigger: "USER_INITIATED",
    requestedCount: req.requestedCount,
    constraints: {
      ...req.constraints,
      mustExcludeIngredientMappingKeys:
        req.constraints.mustExcludeIngredientMappingKeys ?? HARD_CONSTRAINT_KEYS,
    },
    sourcesRequested: requested,
    status: "QUEUED",
    queuedAt: nowIso(),
    startedAt: null,
    completedAt: null,
    candidatesSeen: 0,
    candidatesAfterFilter: 0,
    recipesIngested: 0,
    recipesSkippedDuplicate: 0,
    sourcesSucceeded: [],
    sourcesFailed: [],
    errorSummary: null,
    traceId: `trace-${jobId}`,
    optimisticVersion: 1,
  };
  mutate((s) => ({
    ...s,
    discovery: {
      ...s.discovery,
      jobs: [job, ...s.discovery.jobs],
      scrapeLog: { ...s.discovery.scrapeLog, [jobId]: [] },
      openJobId: jobId,
      cancelRequested: null,
    },
  }));
  setTimeout(() => {
    mutate((s) => {
      const j = findJob(s, jobId);
      if (!j || j.status !== "QUEUED") return s;
      return replaceJob(s, {
        ...j,
        status: "RUNNING",
        startedAt: nowIso(),
        optimisticVersion: j.optimisticVersion + 1,
      });
    });
    setTimeout(() => runDiscoveryStep(jobId, 0), 700);
  }, 1200);
}

/**
 * POST /discovery/jobs/{id}/cancel — three-state semantics (§4): QUEUED is
 * atomically flipped to FAILED; RUNNING returns 200 with the still-RUNNING
 * DTO and a runner flag; terminal → 422 discovery-job-already-terminal.
 */
export function cancelDiscoveryJob(jobId: string): void {
  const job = findJob(state, jobId);
  if (!job) {
    pushToast("404 — job no longer exists", "warn");
    return;
  }
  if (job.status === "QUEUED") {
    mutate((s) => finalizeJob(s, jobId, "FAILED", "cancelled by user"));
    return;
  }
  if (job.status === "RUNNING") {
    mutate((s) => ({
      ...s,
      discovery: { ...s.discovery, cancelRequested: jobId },
    }));
    return;
  }
  pushToast("422 discovery-job-already-terminal — the job already finished", "warn");
}

/* ---- shared selectors --------------------------------------------------------------------------- */

/** Unread count for the rail badge + bell — muted kinds don't count. */
export function selectUnreadCount(s: StoreState): number {
  return s.notifications.reduce(
    (acc, n) =>
      acc + (n.read || s.notificationPrefs.muted.includes(n.kind) ? 0 : 1),
    0,
  );
}
