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
  HOUSEHOLD_ID,
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
import {
  ALL_NOTIFICATION_KINDS,
  resolveSlotConfiguration,
} from "./settingsAdminSeed";
import type {
  ActivityLevel,
  AncestryResponse,
  AnswerClarificationRequest,
  AnyNotificationKind,
  ChangeRoleRequest,
  ClarificationQueryDto,
  ConfidenceTier,
  CostSummaryDto,
  CreateBranchRequest,
  CreateInventoryItemRequest,
  CreateInviteRequest,
  CreateRatingRequest,
  CreateRecipeRequest,
  CreateSubstitutionRequest,
  DailyAggregateDto,
  DecisionLogDto,
  DayDto,
  Destination,
  DirectiveUserModification,
  DiscoveryJobDto,
  EnforcementDirection,
  ExportFormat,
  FeedbackEntryDto,
  GroceryOrderDto,
  GroceryOrderStatus,
  GrocerySubstitutionProposalDto,
  HardConstraintsAuditEntryDto,
  HouseholdInviteDto,
  HouseholdMemberDto,
  HouseholdSettingsAuditEntryDto,
  IngredientNutritionDocument,
  IngredientNutritionDto,
  IntakeDayDto,
  IntakeEntryDto,
  IntakeSlotDto,
  IntakeSnackDto,
  InventoryAuditEntryDto,
  InventoryItemDto,
  LifestyleConfigAuditEntryDto,
  LogSnackRequest,
  LogWasteRequest,
  MarkBoughtRequest,
  MealSlot,
  MealSlotDto,
  MealSlotKey,
  MisclassificationCorrectionDto,
  MockNotificationDto,
  NotificationSeverity,
  NotificationSummaryDto,
  NutritionState,
  PasswordChangeRequest,
  PendingChangeDto,
  PlannerDecisionChainDto,
  ProviderConnectionRequest,
  PinnedReason,
  PlanDto,
  PriceAggregateDto,
  PriceObservationDto,
  ProposedReoptAssignmentsDocument,
  RecipeDiffDto,
  RecipeDto,
  RecipeImportPreview,
  RecipeNutritionResultDto,
  RecipeRatingDto,
  RecipeSubstitutionDto,
  RecipeVersionDto,
  RecordManualPriceRequest,
  RemovedTier1Constraint,
  ReoptSuggestionDto,
  RevertToVersionRequest,
  RoutingDecision,
  RoutingDecisionDto,
  SessionState,
  ShoppingListDto,
  ShoppingListLineDto,
  SlotState,
  StapleStatus,
  StartDiscoveryJobRequest,
  StoreState,
  TargetsDto,
  TasteProfileAuditEntryDto,
  TasteProfileDocument,
  TasteProfileVersionDto,
  Tier1RemovalConfirmationProblem,
  ToastItem,
  UiContextDto,
  UpdateBudgetRequest,
  UpdateHardConstraintsRequest,
  UpdateHouseholdSettingsRequest,
  UpdateInventoryItemRequest,
  UpdateLifestyleConfigRequest,
  UpdateMemberRequest,
  UpdateNotificationPreferenceRequest,
  UpdateRecipeManualEditRequest,
  UpdateTargetsRequest,
  UpsertEquipmentRequest,
  WasteEntryDto,
  WasteSummaryDto,
  WeeklyAggregateDto,
} from "./types";
import { LIVE } from "../live/flag";
import { apiSend, LiveApiError } from "../live/client";

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

/**
 * Live-mode hydration seam: replace store slices with the producer's output and
 * notify subscribers. The live layer (src/live/) calls this on boot to populate
 * the store from the real backend; page components read the store either way.
 */
export function hydrateStore(producer: (s: StoreState) => StoreState): void {
  mutate(producer);
}

/* ---- live mutation seam ---------------------------------------------------- */

/** Re-fetch the live slices into the store after a write lands (the server is
 *  the source of truth; any optimistic local update is reconciled). */
function rehydrateLive(): void {
  void import("../live/hydrate")
    .then((m) => m.hydrateToday())
    .catch(() => {});
}

/** Fire a live write, then reconcile; on failure toast + reconcile (which
 *  reverts any optimistic local update to the server's truth). */
function liveMutation(call: Promise<unknown>, errMsg: string): void {
  call
    .then(() => rehydrateLive())
    .catch((e: unknown) => {
      const status = e instanceof LiveApiError ? ` (${e.status})` : "";
      pushToast(`${errMsg}${status}`, "warn");
      rehydrateLive();
    });
}

/** Read a slice of the store; re-renders when the selected value changes. */
export function useStore<T>(selector: (s: StoreState) => T): T {
  return useSyncExternalStore(subscribe, () => selector(getSnapshot()));
}

let notificationSeq = 100;
let deliverySeq = 100;

/** Legacy in-app event channels (call sites predate the contract rebuild) →
 *  contract NotificationKind. The two Java-only kinds appear here because the
 *  backend genuinely emits them — the OpenAPI enum is missing them (§8 Q1). */
const CHANNEL_KIND: Record<
  "plan" | "recipe" | "grocery" | "order" | "pantry" | "expiry" | "ai",
  AnyNotificationKind
> = {
  plan: "PLANNER_PLAN_GENERATED",
  recipe: "FEEDBACK_CONFIRMATION",
  grocery: "STAPLE_REPLENISHMENT_NEEDED",
  order: "STAPLE_REPLENISHMENT_NEEDED",
  pantry: "PROVISION_ITEM_SPOILED",
  expiry: "PROVISION_ITEM_NEAR_EXPIRY",
  ai: "FEEDBACK_CONFIRMATION",
};

/** Server default deep links per kind (notifications.md §3c) — note these are
 *  the backend's `/app/*` URIs, not IA routes (the §8 Q2 mismatch; the client
 *  maps them via resolveActionTarget). */
const KIND_DEFAULT_TARGET: Record<AnyNotificationKind, string> = {
  PROVISION_ITEM_NEAR_EXPIRY: "/app/provisions/inventory",
  PROVISION_ITEM_SPOILED: "/app/provisions/inventory",
  PROVISION_DEFROST_REMINDER: "/app/provisions/inventory",
  NUTRITION_INTAKE_DIVERGED: "/app/nutrition/intake/today",
  HEALTH_DIRECTIVE_RECEIVED: "/app/nutrition/health-directives/latest",
  PLANNER_PREP_REMINDER: "/app/planner/slots/next",
  PLANNER_REOPT_SUGGESTED: "/app/plans/current",
  PLANNER_PLAN_GENERATED: "/app/plans/current",
  STAPLE_REPLENISHMENT_NEEDED: "/app/provisions/inventory",
  FEEDBACK_CONFIRMATION: "/app/feedback/latest",
};

const KIND_SEVERITY: Partial<Record<AnyNotificationKind, NotificationSeverity>> = {
  PROVISION_ITEM_SPOILED: "URGENT",
  HEALTH_DIRECTIVE_RECEIVED: "URGENT",
  PROVISION_DEFROST_REMINDER: "ATTENTION",
  PLANNER_REOPT_SUGGESTED: "ATTENTION",
};

/**
 * Append (or bundle) a contract NotificationDto row from an in-app event.
 * Mirrors the server pipeline: bursts of the same kind inside the user's
 * debounceWindowMinutes are absorbed into the newest row (`bundleCount`+1
 * plus a DEDUPED_INTO_BUNDLE delivery-log entry); otherwise a fresh UNREAD
 * row lands with a DELIVERED IN_APP log entry.
 */
function pushNotification(
  s: StoreState,
  channel: keyof typeof CHANNEL_KIND,
  title: string,
): StoreState {
  const kind = CHANNEL_KIND[channel];
  const createdAt = nowIso();
  const windowMin = s.notifications.prefs?.debounceWindowMinutes ?? 30;
  const bundleHost = s.notifications.rows.find(
    (n) =>
      n.kind === kind &&
      n.status === "UNREAD" &&
      Date.parse(createdAt) - Date.parse(n.createdAt) < windowMin * 60_000,
  );
  if (bundleHost) {
    const merged: MockNotificationDto = {
      ...bundleHost,
      title,
      bundleCount: bundleHost.bundleCount + 1,
      createdAt,
      version: bundleHost.version + 1,
    };
    return {
      ...s,
      notifications: {
        ...s.notifications,
        rows: [
          merged,
          ...s.notifications.rows.filter((n) => n.id !== bundleHost.id),
        ],
        deliveryLog: {
          ...s.notifications.deliveryLog,
          [merged.id]: [
            {
              id: `dlv-r${++deliverySeq}`,
              notificationId: merged.id,
              channel: "IN_APP",
              outcome: "SKIPPED",
              skipReason: "DEDUPED_INTO_BUNDLE",
              attemptedAt: createdAt,
            },
            ...(s.notifications.deliveryLog[merged.id] ?? []),
          ],
        },
      },
    };
  }
  const id = `ntf-r${++notificationSeq}`;
  const item: MockNotificationDto = {
    id,
    userId: MOCK_USER_ID,
    householdId: HOUSEHOLD_ID,
    kind,
    severity: KIND_SEVERITY[kind] ?? "INFO",
    title,
    body: title,
    payload: { kind },
    status: "UNREAD",
    actionTargetUri: KIND_DEFAULT_TARGET[kind],
    bundleCount: 1,
    bundleKeys: null,
    traceId: null,
    createdAt,
    readAt: null,
    actionedAt: null,
    dismissedAt: null,
    version: 0,
  };
  return {
    ...s,
    notifications: {
      ...s.notifications,
      rows: [item, ...s.notifications.rows],
      deliveryLog: {
        ...s.notifications.deliveryLog,
        [id]: [
          {
            id: `dlv-r${++deliverySeq}`,
            notificationId: id,
            channel: "IN_APP",
            outcome: "DELIVERED",
            skipReason: null,
            attemptedAt: createdAt,
          },
        ],
      },
    },
  };
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
  if (LIVE) {
    liveMutation(
      apiSend("PATCH", `/api/v1/plans/${planId}/slots/${slotId}/state`, {
        newState,
      }),
      "Couldn't update the meal",
    );
  }
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
  const mealTime = kind === "BREAKFAST" ? "08:00" : kind === "DINNER" ? "19:00" : null;
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
    mealTime,
    prepStepAtTime: null,
    // effectiveMealTime/mealTimeSource (#258): generated slots use kind-default
    // serve times, falling back to midday when the kind carries no default.
    effectiveMealTime: mealTime ?? "12:30",
    mealTimeSource: "KIND_DEFAULT",
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

/* ---- grocery: shopping list (groceries.md §3–§4) -----------------------------------------
 * Contract shapes throughout. Mark-bought is the price-observation capture:
 * one-tap deliberately omits the price (a real encounter must be typed in);
 * the popover maps every MarkBoughtRequest field. Bulk distributes a total
 * across estimated line costs as MANUAL_ESTIMATED observations.
 */

/** GET …/shopping-lists/current equivalent (#1). */
export function currentShoppingList(s: StoreState): ShoppingListDto | undefined {
  return s.grocery.lists.find((l) => l.supersededAt == null);
}

export function findShoppingList(
  s: StoreState,
  listId: string,
): ShoppingListDto | undefined {
  return s.grocery.lists.find((l) => l.id === listId);
}

function replaceList(s: StoreState, next: ShoppingListDto): StoreState {
  return {
    ...s,
    grocery: {
      ...s.grocery,
      lists: s.grocery.lists.map((l) => (l.id === next.id ? next : l)),
    },
  };
}

function withLine(
  list: ShoppingListDto,
  lineId: string,
  fn: (line: ShoppingListLineDto) => ShoppingListLineDto,
): ShoppingListDto {
  return {
    ...list,
    lines: list.lines.map((ln) => (ln.id === lineId ? fn(ln) : ln)),
    version: list.version + 1,
  };
}

let observationSeq = 950;

/** Append a price observation + fold it into the aggregate cache rows. */
function recordObservation(
  s: StoreState,
  partial: Omit<
    PriceObservationDto,
    "id" | "userId" | "householdId" | "currency" | "observedAt"
  > & { observedAt?: string },
): { state: StoreState; observationId: string } {
  const id = `po-${++observationSeq}`;
  const obs: PriceObservationDto = {
    id,
    userId: MOCK_USER_ID,
    householdId: HOUSEHOLD_ID,
    currency: "GBP",
    observedAt: partial.observedAt ?? nowStamp(),
    ...partial,
  };
  const key = obs.ingredientMappingKey;
  const pence = obs.paidUnitPence ?? obs.paidTotalPence ?? null;
  const existing = s.grocery.aggregates[key] ?? [];
  const fold = (agg: PriceAggregateDto): PriceAggregateDto => ({
    ...agg,
    pointEstimatePence: pence ?? agg.pointEstimatePence,
    confidence: Math.min(1, (agg.confidence ?? 0.4) + 0.06),
    minPence:
      pence != null && (agg.minPence == null || pence < agg.minPence)
        ? pence
        : agg.minPence,
    maxPence:
      pence != null && (agg.maxPence == null || pence > agg.maxPence)
        ? pence
        : agg.maxPence,
    lastSeenAt: obs.observedAt,
    sampleCount: agg.sampleCount + 1,
    isStale: false,
  });
  const touched = new Set([null, obs.store]);
  let rows = existing.map((agg) =>
    touched.has(agg.store ?? null) ? fold(agg) : agg,
  );
  if (rows.length === 0) {
    rows = [
      fold({
        ingredientMappingKey: key,
        store: null,
        pointEstimatePence: pence,
        confidence: 0.3,
        minPence: pence,
        maxPence: pence,
        minObservedAt: obs.observedAt,
        maxObservedAt: obs.observedAt,
        lastSeenAt: obs.observedAt,
        sampleCount: 0,
        isStale: false,
      }),
    ];
  }
  return {
    state: {
      ...s,
      grocery: {
        ...s.grocery,
        observations: [obs, ...s.grocery.observations],
        aggregates: { ...s.grocery.aggregates, [key]: rows },
      },
    },
    observationId: id,
  };
}

/** Pantry add from a fulfilled line (MarkBoughtResultDto.inventoryItemId). */
function addPantryItemFromLine(
  s: StoreState,
  line: ShoppingListLineDto,
  source: InventoryItemDto["source"],
  sourceRef: string | null,
  costPence: number | null,
): { state: StoreState; itemId: string } {
  const itemId = `inv-${line.id}-${line.boughtAt ?? "now"}`.replace(/[:TZ]/g, "");
  const item: InventoryItemDto = {
    id: itemId,
    userId: MOCK_USER_ID,
    name: line.displayName,
    category: "groceries",
    storageLocation: "CUPBOARD",
    trackingMode: "QUANTITY",
    quantity: line.boughtQuantity ?? line.requestedQuantity,
    unit: line.boughtUnit ?? line.requestedUnit,
    costPaid: costPence == null ? null : costPence / 100,
    status: null,
    isStaple: false,
    expiryDate: null,
    ingredientMappingKey: line.ingredientMappingKey,
    notes: null,
    source,
    sourceRef,
    itemStatus: "ACTIVE",
    freezerExtension: null,
    createdAt: nowStamp(),
    updatedAt: nowStamp(),
    version: 1,
  };
  return {
    state: {
      ...s,
      pantry: { ...s.pantry, items: [item, ...s.pantry.items] },
    },
    itemId,
  };
}

/**
 * POST …/lines/{lineId}/mark-bought (#6) — the full popover path. The
 * one-tap path calls this with the suggested values and NO price (display
 * rule: pre-filling the estimate would feed it back into the learning loop).
 */
export function markBoughtLine(
  listId: string,
  lineId: string,
  req: Omit<MarkBoughtRequest, "shoppingListLineId">,
): void {
  const list = findShoppingList(state, listId);
  const line = list?.lines.find((ln) => ln.id === lineId);
  if (!list || !line) {
    pushToast("404 — line no longer exists; list re-fetched", "warn");
    return;
  }
  if (line.fulfilmentStatus !== "UNFILLED") {
    // 409 already bought → silent re-fetch (spec §8); the checkbox settles.
    return;
  }
  mutate((s) => {
    const l = findShoppingList(s, listId);
    if (!l) return s;
    const boughtAt = req.boughtAt ?? nowStamp();
    let out = replaceList(
      s,
      withLine(l, lineId, (ln) => ({
        ...ln,
        fulfilmentStatus: "BOUGHT",
        boughtQuantity: req.boughtQuantity,
        boughtUnit: req.boughtUnit,
        boughtPricePence: req.boughtPricePence ?? null,
        boughtAt,
        boughtVia: "MANUAL",
        groceryOrderId: null,
      })),
    );
    if (req.boughtPricePence != null) {
      const rec = recordObservation(out, {
        ingredientMappingKey: line.ingredientMappingKey,
        store: req.store ?? "manual",
        providerProductId: null,
        packSizeG: line.suggestedPackSizeG ?? null,
        packCount: line.suggestedPackCount ?? null,
        quantity: req.boughtQuantity,
        quantityUnit: req.boughtUnit,
        paidUnitPence: null,
        paidTotalPence: req.boughtPricePence,
        source: "PAID",
        confidenceWeight: 1,
        groceryOrderId: null,
        shoppingListLineId: lineId,
        observedAt: boughtAt,
        note: "mark-bought",
      });
      out = rec.state;
      pushToast("Price recorded — feeds your price history");
    }
    if (l.pantryTrackingEnabled) {
      out = addPantryItemFromLine(
        out,
        { ...line, boughtQuantity: req.boughtQuantity, boughtUnit: req.boughtUnit, boughtAt },
        "OTHER_SHOP",
        null,
        req.boughtPricePence ?? null,
      ).state;
      pushToast("Added to your pantry");
    }
    if (
      req.boughtUnit === line.requestedUnit &&
      req.boughtQuantity > line.requestedQuantity
    ) {
      // MarkBoughtResultDto.note — over-mark warning (buying more is allowed).
      pushToast(
        `Bought more than the list asked (${req.boughtQuantity} vs ${line.requestedQuantity} ${line.requestedUnit}) — recorded anyway`,
        "warn",
      );
    }
    return out;
  });
}

/** The one-tap checkbox: suggested pack values, price deliberately omitted. */
export function markBoughtOneTap(listId: string, lineId: string): void {
  const list = findShoppingList(state, listId);
  const line = list?.lines.find((ln) => ln.id === lineId);
  if (!line) return;
  const packQty =
    line.suggestedPackCount != null && line.suggestedPackSizeG != null
      ? line.suggestedPackCount * line.suggestedPackSizeG
      : null;
  markBoughtLine(listId, lineId, {
    boughtQuantity: packQty ?? line.requestedQuantity,
    boughtUnit: (packQty != null
      ? "g"
      : asBoughtUnit(line.requestedUnit)) as MarkBoughtRequest["boughtUnit"],
    boughtPricePence: null,
    store: null,
    boughtAt: null,
  });
}

const BOUGHT_UNITS: ReadonlyArray<MarkBoughtRequest["boughtUnit"]> = [
  "g", "kg", "ml", "l", "items", "pt", "tsp", "tbsp", "cup",
];

export function asBoughtUnit(unit: string): MarkBoughtRequest["boughtUnit"] {
  return (
    BOUGHT_UNITS.find((u) => u === unit) ?? "items"
  );
}

/** POST …/bulk-mark-bought (#7) — total-spend proportional distribution. */
export function bulkMarkBought(
  listId: string,
  lineIds: string[],
  totalSpendPence: number | null,
  store: string | null,
): void {
  const list = findShoppingList(state, listId);
  if (!list || lineIds.length === 0) return;
  const targets = list.lines.filter(
    (ln) => lineIds.includes(ln.id) && ln.fulfilmentStatus === "UNFILLED",
  );
  if (targets.length === 0) return;

  // Proportional to estimated line costs; uniform share for unpriced lines.
  const priced = targets.filter((ln) => ln.estimatedLinePence != null);
  const avg =
    priced.length > 0
      ? priced.reduce((acc, ln) => acc + (ln.estimatedLinePence ?? 0), 0) /
        priced.length
      : 1;
  const weights = new Map(
    targets.map((ln) => [ln.id, ln.estimatedLinePence ?? avg]),
  );
  const sumW = [...weights.values()].reduce((a, b) => a + b, 0);

  mutate((s) => {
    let l = findShoppingList(s, listId);
    if (!l) return s;
    const boughtAt = nowStamp();
    let out = s;
    for (const target of targets) {
      const share =
        totalSpendPence == null
          ? null
          : Math.round((totalSpendPence * (weights.get(target.id) ?? avg)) / sumW);
      l = findShoppingList(out, listId);
      if (!l) return out;
      out = replaceList(
        out,
        withLine(l, target.id, (ln) => ({
          ...ln,
          fulfilmentStatus: "BOUGHT",
          boughtQuantity: ln.requestedQuantity,
          boughtUnit: ln.requestedUnit,
          boughtPricePence: share,
          boughtAt,
          boughtVia: "BULK_TOTAL",
          groceryOrderId: null,
        })),
      );
      if (share != null) {
        out = recordObservation(out, {
          ingredientMappingKey: target.ingredientMappingKey,
          store: store ?? "manual",
          providerProductId: null,
          packSizeG: target.suggestedPackSizeG ?? null,
          packCount: target.suggestedPackCount ?? null,
          quantity: target.requestedQuantity,
          quantityUnit: target.requestedUnit,
          paidUnitPence: null,
          paidTotalPence: share,
          source: "MANUAL_ESTIMATED",
          confidenceWeight: 0.5,
          groceryOrderId: null,
          shoppingListLineId: target.id,
          observedAt: boughtAt,
          note: "distributed from bulk total",
        }).state;
      }
      if (out.pantry && l.pantryTrackingEnabled) {
        out = addPantryItemFromLine(
          out,
          { ...target, boughtQuantity: target.requestedQuantity, boughtUnit: target.requestedUnit, boughtAt },
          "OTHER_SHOP",
          null,
          share,
        ).state;
      }
    }
    pushToast(
      totalSpendPence == null
        ? `${targets.length} marked bought`
        : `${targets.length} marked bought · £${(totalSpendPence / 100).toFixed(2)} distributed across estimates (lower-confidence observations)`,
    );
    return out;
  });
}

/**
 * POST …/undo-mark-bought (#8) — MANUAL/BULK_TOTAL rows only. The contract
 * does NOT reverse the pantry add (groceries.md §8 Q4); the confirm copy says
 * so before this is called.
 */
export function undoMarkBought(listId: string, lineId: string): void {
  const list = findShoppingList(state, listId);
  const line = list?.lines.find((ln) => ln.id === lineId);
  if (!list || !line) {
    pushToast("404 — line no longer exists; list re-fetched", "warn");
    return;
  }
  if (
    line.fulfilmentStatus !== "BOUGHT" ||
    (line.boughtVia !== "MANUAL" && line.boughtVia !== "BULK_TOTAL")
  ) {
    pushToast("409 — not currently bought; list re-fetched", "warn");
    return;
  }
  mutate((s) => {
    const l = findShoppingList(s, listId);
    if (!l) return s;
    return replaceList(
      s,
      withLine(l, lineId, (ln) => ({
        ...ln,
        fulfilmentStatus: "UNFILLED",
        boughtQuantity: null,
        boughtUnit: null,
        boughtPricePence: null,
        boughtAt: null,
        boughtVia: null,
        groceryOrderId: null,
      })),
    );
  });
  pushToast(
    "Mark removed — a compensating price note was written. The pantry item is NOT removed automatically; correct it in Pantry if needed.",
    "warn",
  );
}

/**
 * POST …/shopping-lists/recalculate (#4) — idempotent per (planId,
 * planGeneration): within one generation the server returns the existing
 * list, so a re-tap is a no-op (groceries.md §8 Q2 — pantry drift cannot be
 * picked up without a new plan generation).
 */
export function recalculateShoppingList(): void {
  const s = state;
  const active = activePlanForWeek(s, CURRENT_WEEK_START);
  if (!active) {
    pushToast("404 — no active plan generation; generate a plan first", "warn");
    return;
  }
  const current = currentShoppingList(s);
  if (
    current &&
    current.planId === active.id &&
    current.planGeneration === active.generation
  ) {
    pushToast(
      "Already up to date — recalculate is idempotent within a plan generation (200 returned the existing list)",
    );
    return;
  }
  // The plan advanced a generation — derive a fresh list and supersede.
  mutate((st) => {
    const cur = currentShoppingList(st);
    const newList: ShoppingListDto = {
      ...(cur ?? st.grocery.lists[0]),
      id: `sl-${active.id}`,
      planId: active.id,
      planGeneration: active.generation,
      generatedAt: nowStamp(),
      supersededAt: null,
      notes: "Re-derived from the latest plan generation",
      lines: (cur?.lines ?? []).map((ln) => ({ ...ln })),
      version: 1,
    };
    const out: StoreState = {
      ...st,
      grocery: {
        ...st.grocery,
        lists: [
          newList,
          ...st.grocery.lists.map((l) =>
            l.supersededAt == null ? { ...l, supersededAt: nowStamp() } : l,
          ),
        ],
      },
    };
    return pushNotification(
      out,
      "grocery",
      `Shopping list re-derived for plan generation ${active.generation}`,
    );
  });
  pushToast("List re-derived — bought marks on this generation are kept");
}

/** GET …/{id}/export (#5) — content built per ExportFormat (mock server). */
export function buildListExport(
  list: ShoppingListDto,
  format: ExportFormat,
): string {
  const open = list.lines.filter((ln) => ln.fulfilmentStatus === "UNFILLED");
  const lineTxt = (ln: ShoppingListLineDto): string =>
    `${ln.displayName} — ${ln.requestedQuantity} ${ln.requestedUnit}`;
  switch (format) {
    case "PLAIN_TEXT":
      return open.map(lineTxt).join("\n");
    case "MARKDOWN":
      return open.map((ln) => `- [ ] ${lineTxt(ln)}`).join("\n");
    case "CSV":
      return [
        "name,quantity,unit,estimated_pence",
        ...open.map(
          (ln) =>
            `"${ln.displayName}",${ln.requestedQuantity},${ln.requestedUnit},${ln.estimatedLinePence ?? ""}`,
        ),
      ].join("\n");
    case "PRINTABLE_HTML":
      return `<h1>Shopping list — generation ${list.planGeneration}</h1><ul>${open
        .map((ln) => `<li>${lineTxt(ln)}</li>`)
        .join("")}</ul>`;
  }
}

/* ---- grocery: orders (groceries.md §5) ------------------------------------------------
 * The 11-status contract machine. Legal edges only; everything else 409s.
 * placeOrder PAUSES at PLACED (delivery_slot_required) — refresh-status is
 * the only advance path. The provider never auto-confirms (HLD).
 */

export function findOrder(s: StoreState, orderId: string): GroceryOrderDto | undefined {
  return s.grocery.orders.find((o) => o.id === orderId);
}

function replaceOrder(s: StoreState, next: GroceryOrderDto): StoreState {
  return {
    ...s,
    grocery: {
      ...s.grocery,
      orders: s.grocery.orders.map((o) => (o.id === next.id ? next : o)),
    },
  };
}

const ORDER_TERMINAL: GroceryOrderStatus[] = ["RECONCILED", "CANCELLED", "ARCHIVED"];

let orderSeq = 110;

/** POST /grocery/orders (#10) — creates a DRAFT from the current list. */
export function createGroceryOrder(): void {
  const s = state;
  if (!s.grocery.providerState?.enabled) {
    pushToast("422 — no provider configured; connect one in Settings", "warn");
    return;
  }
  const list = currentShoppingList(s);
  if (!list) {
    pushToast("404 — no current shopping list", "warn");
    return;
  }
  const open = list.lines.filter((ln) => ln.fulfilmentStatus === "UNFILLED");
  if (open.length === 0) {
    pushToast("Nothing left to order — every line is decided");
    return;
  }
  const id = `ord-${++orderSeq}`;
  mutate((st) => ({
    ...st,
    grocery: {
      ...st.grocery,
      orders: [
        {
          id,
          userId: MOCK_USER_ID,
          householdId: HOUSEHOLD_ID,
          shoppingListId: list.id,
          providerKey: st.grocery.providerState?.providerKey ?? "tesco",
          providerOrderId: null,
          status: "DRAFT",
          statusReason: null,
          quotedTotalPence: null,
          confirmedTotalPence: null,
          paidTotalPence: null,
          currency: "GBP",
          deliverySlotStart: null,
          deliverySlotEnd: null,
          confirmLink: null,
          placedAt: null,
          confirmedAt: null,
          deliveredAt: null,
          reconciledAt: null,
          cancelledAt: null,
          cancelReason: null,
          lastStatusCheckAt: null,
          lines: open.map((ln) => ({
            id: `ol-${id}-${ln.id}`,
            shoppingListLineId: ln.id,
            providerProductId: null,
            ingredientMappingKey: ln.ingredientMappingKey,
            displayName: ln.displayName,
            quantityRequested: ln.requestedQuantity,
            quantityUnit: ln.requestedUnit,
            packSizeG: ln.suggestedPackSizeG ?? null,
            packCountRequested: ln.suggestedPackCount ?? null,
            packCountDelivered: null,
            quotedUnitPence: null,
            confirmedUnitPence: null,
            paidUnitPence: null,
            lineStatus: "QUEUED",
            note: null,
          })),
          outstandingProposals: null,
          version: 1,
        },
        ...st.grocery.orders,
      ],
    },
  }));
  pushToast(`Draft order created from ${open.length} open lines`);
}

function illegalTransition(action: string): void {
  pushToast(
    `409 — ${action} is not legal from this state; order re-fetched (it changed elsewhere)`,
    "warn",
  );
}

/** POST …/quote (#12) — DRAFT → QUOTED (also "Try quote again" from PROVIDER_UNAVAILABLE). */
export function quoteOrder(orderId: string): void {
  const order = findOrder(state, orderId);
  if (!order) return;
  if (order.status !== "DRAFT" && order.status !== "PROVIDER_UNAVAILABLE") {
    illegalTransition("get-quote");
    return;
  }
  mutate((s) => {
    const o = findOrder(s, orderId);
    if (!o) return s;
    const lines = o.lines.map((ln) => ({
      ...ln,
      quotedUnitPence:
        ln.quotedUnitPence ??
        Math.max(60, Math.round((ln.packSizeG ?? 250) * 0.55)),
      lineStatus: "QUEUED" as const,
    }));
    const total = lines.reduce(
      (acc, ln) =>
        acc + (ln.quotedUnitPence ?? 0) * (ln.packCountRequested ?? 1),
      0,
    );
    return replaceOrder(s, {
      ...o,
      status: "QUOTED",
      statusReason: null,
      quotedTotalPence: total,
      lastStatusCheckAt: nowStamp(),
      lines,
      version: o.version + 1,
    });
  });
  pushToast("Quote received — prices fed into your price cache");
}

/** POST …/place (#13) — QUOTED → PLACED (slot-required pause). */
export function placeOrder(orderId: string): void {
  const order = findOrder(state, orderId);
  if (!order) return;
  if (order.status !== "QUOTED") {
    illegalTransition("place");
    return;
  }
  mutate((s) => {
    const o = findOrder(s, orderId);
    if (!o) return s;
    return replaceOrder(s, {
      ...o,
      status: "PLACED",
      statusReason: "delivery_slot_required",
      confirmLink: "https://www.tesco.com/groceries/trolley",
      placedAt: nowStamp(),
      lastStatusCheckAt: nowStamp(),
      lines: o.lines.map((ln) => ({ ...ln, lineStatus: "ADDED" as const })),
      version: o.version + 1,
    });
  });
  pushToast("Basket built — pick a delivery slot in the Tesco basket");
}

/** Reconcile (server-side, automatic): fulfil source lines + pantry import. */
function reconcileOrder(s: StoreState, orderId: string): StoreState {
  const o = findOrder(s, orderId);
  if (!o) return s;
  const paidTotal = o.confirmedTotalPence ?? o.quotedTotalPence ?? null;
  let out = replaceOrder(s, {
    ...o,
    status: "RECONCILED",
    reconciledAt: nowStamp(),
    paidTotalPence: paidTotal,
    lastStatusCheckAt: nowStamp(),
    lines: o.lines.map((ln) =>
      ln.lineStatus === "UNAVAILABLE" || ln.lineStatus === "REJECTED"
        ? ln
        : {
            ...ln,
            lineStatus:
              ln.lineStatus === "SUBSTITUTED" ? ln.lineStatus : ("DELIVERED" as const),
            paidUnitPence: ln.confirmedUnitPence ?? ln.quotedUnitPence,
            packCountDelivered: ln.packCountDelivered ?? ln.packCountRequested,
          },
    ),
    version: o.version + 1,
  });
  // Tier-2 effect: order fulfilment writes through to the source list lines.
  const list = findShoppingList(out, o.shoppingListId);
  if (list) {
    let nextList = list;
    for (const ln of o.lines) {
      if (!ln.shoppingListLineId) continue;
      if (ln.lineStatus === "UNAVAILABLE" || ln.lineStatus === "REJECTED") continue;
      const pence =
        (ln.confirmedUnitPence ?? ln.quotedUnitPence ?? 0) *
        (ln.packCountDelivered ?? ln.packCountRequested ?? 1);
      nextList = withLine(nextList, ln.shoppingListLineId, (sl) =>
        sl.fulfilmentStatus === "UNFILLED" || sl.fulfilmentStatus === "PARTIAL"
          ? {
              ...sl,
              fulfilmentStatus:
                ln.lineStatus === "SUBSTITUTED" ? "SUBSTITUTED" : "BOUGHT",
              boughtQuantity: sl.requestedQuantity,
              boughtUnit: sl.requestedUnit,
              boughtPricePence: pence || null,
              boughtAt: nowStamp(),
              boughtVia: "ORDER",
              groceryOrderId: o.id,
            }
          : sl,
      );
    }
    out = replaceList(out, nextList);
  }
  return pushNotification(
    out,
    "order",
    `${o.providerKey} order reconciled — pantry updated, prices recorded`,
  );
}

/** POST …/refresh-status (#15) — pulls provider status (the only PLACED advance). */
export function refreshOrderStatus(orderId: string): void {
  const order = findOrder(state, orderId);
  if (!order) return;
  if (ORDER_TERMINAL.includes(order.status)) {
    illegalTransition("refresh-status");
    return;
  }
  mutate((s) => {
    const o = findOrder(s, orderId);
    if (!o) return s;
    const checked = { lastStatusCheckAt: nowStamp(), version: o.version + 1 };
    switch (o.status) {
      case "PLACED": {
        // Mock provider: the user picked a slot since the last check.
        const out = replaceOrder(s, {
          ...o,
          ...checked,
          status: "AWAITING_USER_CONFIRMATION",
          statusReason: null,
          deliverySlotStart: "2026-06-12T18:00:00Z",
          deliverySlotEnd: "2026-06-12T19:00:00Z",
        });
        pushToast(
          "Delivery slot detected — confirm the order in Tesco (we never confirm for you)",
        );
        return out;
      }
      case "CONFIRMED": {
        const delivered = replaceOrder(s, {
          ...o,
          ...checked,
          status: "DELIVERED",
          deliveredAt: nowStamp(),
        });
        // No proposals on this mock path → reconciliation runs immediately.
        return reconcileOrder(delivered, orderId);
      }
      case "PLACED_PARTIAL":
        pushToast(
          "Status unchanged — finish adding the missing items in the Tesco basket",
          "warn",
        );
        return replaceOrder(s, { ...o, ...checked });
      default:
        pushToast("Status checked — no change");
        return replaceOrder(s, { ...o, ...checked });
    }
  });
}

/** POST …/mark-user-confirmed (#14) — AWAITING_USER_CONFIRMATION → CONFIRMED. */
export function markUserConfirmed(orderId: string): void {
  const order = findOrder(state, orderId);
  if (!order) return;
  if (order.status !== "AWAITING_USER_CONFIRMATION") {
    illegalTransition("mark-user-confirmed");
    return;
  }
  mutate((s) => {
    const o = findOrder(s, orderId);
    if (!o) return s;
    const out = replaceOrder(s, {
      ...o,
      status: "CONFIRMED",
      confirmedAt: nowStamp(),
      confirmedTotalPence: o.quotedTotalPence,
      providerOrderId: o.providerOrderId ?? `TESCO-${66100 + orderSeq}`,
      lastStatusCheckAt: nowStamp(),
      version: o.version + 1,
    });
    return pushNotification(
      out,
      "order",
      `${o.providerKey} order confirmed — delivery ${o.deliverySlotStart ? "slot booked" : "pending slot"}`,
    );
  });
}

/** POST …/mark-delivered (#16) — CONFIRMED → DELIVERED ("It arrived"). */
export function markOrderDelivered(orderId: string): void {
  const order = findOrder(state, orderId);
  if (!order) return;
  if (order.status !== "CONFIRMED") {
    illegalTransition("mark-delivered");
    return;
  }
  mutate((s) => {
    const o = findOrder(s, orderId);
    if (!o) return s;
    const delivered = replaceOrder(s, {
      ...o,
      status: "DELIVERED",
      deliveredAt: nowStamp(),
      lastStatusCheckAt: nowStamp(),
      version: o.version + 1,
    });
    return reconcileOrder(delivered, orderId);
  });
}

/** POST …/cancel (#17) — legal from every state until RECONCILED. */
export function cancelGroceryOrder(orderId: string, reason: string): void {
  const order = findOrder(state, orderId);
  if (!order) return;
  if (ORDER_TERMINAL.includes(order.status)) {
    illegalTransition("cancel");
    return;
  }
  mutate((s) => {
    const o = findOrder(s, orderId);
    if (!o) return s;
    const out = replaceOrder(s, {
      ...o,
      status: "CANCELLED",
      cancelledAt: nowStamp(),
      cancelReason: reason.trim().slice(0, 64) || null,
      outstandingProposals: null,
      version: o.version + 1,
    });
    return pushNotification(out, "order", `${o.providerKey} order cancelled`);
  });
}

/* ---- grocery: substitution review (groceries.md §5d) ---------------------------------- */

/**
 * POST …/substitutions/{proposalId}/resolve (#19). Accept → the substitute
 * enters the pantry; reject → logged as wasted-on-arrival + planner notified
 * the original is unmet. Resolving the LAST proposal triggers reconciliation
 * server-side.
 */
export function resolveSubstitution(
  orderId: string,
  proposalId: string,
  decision: "ACCEPTED" | "REJECTED",
): void {
  const proposals = state.grocery.proposalsByOrder[orderId] ?? [];
  const proposal = proposals.find((p) => p.id === proposalId);
  if (!proposal) {
    pushToast("404 — proposal no longer exists; re-fetched", "warn");
    return;
  }
  if (
    proposal.proposalStatus !== "PENDING_USER_REVIEW" &&
    proposal.proposalStatus !== "UNPARSED"
  ) {
    pushToast("409 — already resolved elsewhere; re-fetched", "warn");
    return;
  }
  mutate((s) => {
    const o = findOrder(s, orderId);
    if (!o) return s;
    const resolved: GrocerySubstitutionProposalDto = {
      ...proposal,
      proposalStatus: decision,
      resolvedAt: nowStamp(),
      resolvedByUserId: MOCK_USER_ID,
    };
    const remaining = (o.outstandingProposals ?? []).filter(
      (p) => p.id !== proposalId,
    );
    let out: StoreState = replaceOrder(s, {
      ...o,
      outstandingProposals: remaining.length > 0 ? remaining : [],
      lines: o.lines.map((ln) =>
        ln.id === proposal.groceryOrderLineId && decision === "REJECTED"
          ? { ...ln, lineStatus: "REJECTED" as const }
          : ln,
      ),
      version: o.version + 1,
    });
    out = {
      ...out,
      grocery: {
        ...out.grocery,
        proposalsByOrder: {
          ...out.grocery.proposalsByOrder,
          [orderId]: proposals.map((p) => (p.id === proposalId ? resolved : p)),
        },
      },
    };

    if (decision === "ACCEPTED") {
      const item: InventoryItemDto = {
        id: `inv-sub-${proposalId}`,
        userId: MOCK_USER_ID,
        name: proposal.substituteDisplayName,
        category: "groceries",
        storageLocation: "FRIDGE",
        trackingMode: "QUANTITY",
        quantity: proposal.substituteQuantity ?? 1,
        unit: proposal.substituteUnit ?? "items",
        costPaid:
          proposal.substituteUnitPence == null
            ? null
            : proposal.substituteUnitPence / 100,
        status: null,
        isStaple: false,
        expiryDate: null,
        ingredientMappingKey: proposal.substituteIngredientMappingKey ?? null,
        notes: `substitute for ${proposal.originalDisplayName}`,
        source: "TESCO_ORDER",
        sourceRef: findOrder(out, orderId)?.providerOrderId ?? orderId,
        itemStatus: "ACTIVE",
        freezerExtension: null,
        createdAt: nowStamp(),
        updatedAt: nowStamp(),
        version: 1,
      };
      out = { ...out, pantry: { ...out.pantry, items: [item, ...out.pantry.items] } };
      pushToast(
        `Substitution accepted — ${proposal.substituteDisplayName.toLowerCase()} goes into your pantry`,
      );
    } else {
      // Wasted-on-arrival log + planner notified the original is unmet.
      const wasteEntry: WasteEntryDto = {
        id: `we-sub-${proposalId}`,
        userId: MOCK_USER_ID,
        inventoryItemId: null,
        itemName: proposal.substituteDisplayName,
        quantity: proposal.substituteQuantity ?? null,
        unit: proposal.substituteUnit ?? null,
        reason: "DIDNT_LIKE",
        costEstimate:
          proposal.substituteUnitPence == null
            ? null
            : proposal.substituteUnitPence / 100,
        occurredOn: MOCK_TODAY_ISO,
        notes: "rejected substitution — wasted on arrival",
        createdAt: nowStamp(),
      };
      out = {
        ...out,
        pantry: { ...out.pantry, waste: [wasteEntry, ...out.pantry.waste] },
      };
      out = pushNotification(
        out,
        "grocery",
        `Substitution rejected — planner notified ${proposal.originalDisplayName.toLowerCase()} is unmet`,
      );
      pushToast(
        "Substitution rejected — logged as wasted-on-arrival; the planner may suggest re-optimising affected meals",
        "warn",
      );
    }

    if (remaining.length === 0) {
      out = reconcileOrder(out, orderId);
      pushToast("All substitutions resolved — order reconciled");
    }
    return out;
  });
}

/* ---- grocery: price history (groceries.md §6) ------------------------------------------- */

/** POST /grocery/price-history/observations/manual (#25). */
export function recordManualPrice(req: RecordManualPriceRequest): void {
  mutate((s) => {
    const rec = recordObservation(s, {
      ingredientMappingKey: req.ingredientMappingKey,
      store: req.store,
      providerProductId: null,
      packSizeG: null,
      packCount: null,
      quantity: req.quantity ?? null,
      quantityUnit: req.quantityUnit ?? null,
      paidUnitPence: null,
      paidTotalPence: req.paidTotalPence ?? null,
      source: "MANUAL",
      confidenceWeight: 0.7,
      groceryOrderId: null,
      shoppingListLineId: null,
      observedAt: req.observedAt ?? undefined,
      note: null,
    });
    return rec.state;
  });
  pushToast("201 — price recorded (source MANUAL, weight 0.7)");
}

/**
 * POST /grocery/price-history/refresh (#26). useProviderQuote rides the
 * provider gate; false = re-read aggregates only (no tokens spent).
 */
export function refreshPrices(): void {
  const s = state;
  const list = currentShoppingList(s);
  const keys = (list?.lines ?? [])
    .filter((ln) => ln.fulfilmentStatus === "UNFILLED")
    .map((ln) => ln.ingredientMappingKey)
    .slice(0, 200);
  const useProviderQuote = s.grocery.providerState?.enabled === true;
  if (!useProviderQuote) {
    pushToast("Aggregates re-read — no provider quote requested (0 observations)");
    return;
  }
  mutate((st) => {
    let out = st;
    let written = 0;
    for (const key of keys) {
      const staleAgg = (out.grocery.aggregates[key] ?? []).some((a) => a.isStale);
      const line = currentShoppingList(out)?.lines.find(
        (ln) => ln.ingredientMappingKey === key,
      );
      if (!staleAgg && !(line?.isStaleEstimate ?? false)) continue;
      out = recordObservation(out, {
        ingredientMappingKey: key,
        store: "tesco",
        providerProductId: null,
        packSizeG: line?.suggestedPackSizeG ?? null,
        packCount: line?.suggestedPackCount ?? null,
        quantity: line?.requestedQuantity ?? null,
        quantityUnit: line?.requestedUnit ?? null,
        paidUnitPence: line?.estimatedUnitPence ?? null,
        paidTotalPence: line?.estimatedLinePence ?? null,
        source: "QUOTE",
        confidenceWeight: 0.8,
        groceryOrderId: null,
        shoppingListLineId: line?.id ?? null,
        note: "price refresh",
      }).state;
      written += 1;
    }
    // Line estimates may move → the current list re-fetches fresh (#1).
    const cur = currentShoppingList(out);
    if (cur) {
      out = replaceList(out, {
        ...cur,
        staleIngredientCount: 0,
        lines: cur.lines.map((ln) => ({ ...ln, isStaleEstimate: false })),
        version: cur.version + 1,
      });
    }
    pushToast(
      `${keys.length} ingredients refreshed · ${written} new observations`,
    );
    return out;
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

/* ---- pantry: inventory (pantry.md §3) ------------------------------------------------------
 * Contract shapes throughout. The list read (#1) returns ACTIVE rows only —
 * spoiled/exhausted/wasted rows leave the list on mutation (no history view,
 * spec §9 Q2). Every user write lands an audit entry (HLD: "overrides are
 * logged with timestamps").
 */

export function findInventoryItem(
  s: StoreState,
  itemId: string,
): InventoryItemDto | undefined {
  return s.pantry.items.find((it) => it.id === itemId);
}

function replaceItem(s: StoreState, next: InventoryItemDto): StoreState {
  return {
    ...s,
    pantry: {
      ...s.pantry,
      items: s.pantry.items.map((it) => (it.id === next.id ? next : it)),
    },
  };
}

let auditSeq = 50;

function appendAudit(
  s: StoreState,
  itemId: string,
  actor: InventoryAuditEntryDto["actor"],
  fieldChanged: string,
  previousValue: unknown,
  newValue: unknown,
): StoreState {
  const entry: InventoryAuditEntryDto = {
    id: `aud-${++auditSeq}`,
    inventoryItemId: itemId,
    actor,
    actorUserId: actor === "USER" ? MOCK_USER_ID : null,
    fieldChanged,
    previousValue,
    newValue,
    occurredAt: nowStamp(),
  };
  return {
    ...s,
    pantry: {
      ...s.pantry,
      auditByItem: {
        ...s.pantry.auditByItem,
        [itemId]: [entry, ...(s.pantry.auditByItem[itemId] ?? [])],
      },
    },
  };
}

/**
 * PATCH …/inventory/{itemId}/quantity (#5) — ABSOLUTE newQuantity (not a
 * delta) + expectedVersion; no unit field (unit changes ride the full PUT).
 * The stepper computes current ± step → newQuantity before calling.
 * 409 stale-version path: re-fetch + one silent retry (mock has no concurrent
 * writer, so the retry always lands).
 */
export function adjustItemQuantity(itemId: string, newQuantity: number): void {
  const item = findInventoryItem(state, itemId);
  if (!item || item.itemStatus !== "ACTIVE") {
    pushToast("404 — no longer in your pantry; re-fetched", "warn");
    return;
  }
  if (item.trackingMode !== "QUANTITY") {
    pushToast("400 — quantity adjust is not valid on a status-tracked item", "warn");
    return;
  }
  const next = Math.max(0, newQuantity);
  mutate((s) => {
    const it = findInventoryItem(s, itemId);
    if (!it) return s;
    const out = replaceItem(s, {
      ...it,
      quantity: next,
      updatedAt: nowStamp(),
      version: it.version + 1,
    });
    return appendAudit(out, itemId, "USER", "quantity", it.quantity, next);
  });
}

/**
 * Staple status tap — there is NO focused status endpoint: the tap echoes the
 * whole item back through PUT …/{itemId} with expectedVersion (pantry.md §9
 * Q1). Legal taps cycle STOCKED → LOW → OUT; replenishment back to STOCKED
 * happens via grocery import.
 */
export function cycleStapleStatus(itemId: string): void {
  const item = findInventoryItem(state, itemId);
  if (!item || item.trackingMode !== "STATUS") return;
  if (item.status === "OUT") {
    pushToast(
      "Already out — it returns to stocked when a shop replenishes it",
      "warn",
    );
    return;
  }
  const next: StapleStatus = item.status === "STOCKED" ? "LOW" : "OUT";
  mutate((s) => {
    const it = findInventoryItem(s, itemId);
    if (!it) return s;
    const out = replaceItem(s, {
      ...it,
      status: next,
      updatedAt: nowStamp(),
      version: it.version + 1, // full PUT with expectedVersion
    });
    return appendAudit(out, itemId, "USER", "status", it.status, next);
  });
  if (item.isStaple) {
    pushToast(
      `${item.name} marked ${next.toLowerCase()} — staples at low or out are auto-added to the next shopping list`,
    );
  }
}

let inventorySeqNo = 200;

/** POST /provisions/inventory (#2) — add-item form save (201). */
export function createInventoryItem(req: CreateInventoryItemRequest): boolean {
  const statusMode = req.storageLocation === "SPICE_RACK";
  if (statusMode && req.trackingMode !== "STATUS") {
    pushToast("400 — spice-rack items must be status-tracked", "warn");
    return false;
  }
  if (!statusMode && req.trackingMode !== "QUANTITY") {
    pushToast("400 — fridge/freezer/cupboard items must be quantity-tracked", "warn");
    return false;
  }
  if (req.freezerExtension && req.storageLocation !== "FREEZER") {
    pushToast("400 — freezer details are only valid on freezer items", "warn");
    return false;
  }
  const id = `inv-new-${++inventorySeqNo}`;
  mutate((s) => {
    const item: InventoryItemDto = {
      id,
      userId: MOCK_USER_ID,
      name: req.name,
      category: req.category,
      storageLocation: req.storageLocation,
      trackingMode: req.trackingMode,
      quantity: req.quantity ?? null,
      unit: req.unit ?? null,
      costPaid: req.costPaid ?? null,
      status: req.status ?? (statusMode ? "STOCKED" : null),
      isStaple: req.isStaple,
      expiryDate: req.expiryDate ?? null,
      ingredientMappingKey: req.ingredientMappingKey ?? null,
      notes: req.notes ?? null,
      source: req.source,
      sourceRef: req.sourceRef ?? null,
      itemStatus: "ACTIVE",
      freezerExtension: req.freezerExtension ?? null,
      createdAt: nowStamp(),
      updatedAt: nowStamp(),
      version: 1,
    };
    const out: StoreState = {
      ...s,
      pantry: { ...s.pantry, items: [item, ...s.pantry.items] },
    };
    return appendAudit(out, id, "USER", "created", null, req.name);
  });
  pushToast(`${req.name} added to your pantry`);
  return true;
}

/** PUT …/inventory/{itemId} (#4) — full replacement with expectedVersion. */
export function updateInventoryItem(
  itemId: string,
  req: UpdateInventoryItemRequest,
): boolean {
  const item = findInventoryItem(state, itemId);
  if (!item) {
    pushToast("404 — no longer in your pantry; re-fetched", "warn");
    return false;
  }
  if (req.expectedVersion !== item.version) {
    pushToast("409 — changed elsewhere; review and re-save", "warn");
    return false;
  }
  const statusMode = req.storageLocation === "SPICE_RACK";
  if ((statusMode && req.trackingMode !== "STATUS") || (!statusMode && req.trackingMode !== "QUANTITY")) {
    pushToast("400 — tracking mode does not match the storage location", "warn");
    return false;
  }
  if (req.freezerExtension && req.storageLocation !== "FREEZER") {
    pushToast("400 — freezer details are only valid on freezer items", "warn");
    return false;
  }
  mutate((s) => {
    const it = findInventoryItem(s, itemId);
    if (!it) return s;
    const out = replaceItem(s, {
      ...it,
      name: req.name,
      category: req.category,
      storageLocation: req.storageLocation,
      trackingMode: req.trackingMode,
      quantity: req.quantity ?? null,
      unit: req.unit ?? null,
      costPaid: req.costPaid ?? null,
      status: req.status ?? null,
      isStaple: req.isStaple,
      expiryDate: req.expiryDate ?? null,
      ingredientMappingKey: req.ingredientMappingKey ?? null,
      notes: req.notes ?? null,
      itemStatus: req.itemStatus,
      freezerExtension: req.freezerExtension ?? null,
      updatedAt: nowStamp(),
      version: it.version + 1,
    });
    return appendAudit(out, itemId, "USER", "edited", it.name, req.name);
  });
  pushToast(`${req.name} saved`);
  return true;
}

/** DELETE …/inventory/{itemId} (#6) — soft delete, NO waste entry (204). */
export function removeInventoryItem(itemId: string): void {
  const item = findInventoryItem(state, itemId);
  if (!item) return;
  mutate((s) => {
    const it = findInventoryItem(s, itemId);
    if (!it) return s;
    const out = replaceItem(s, {
      ...it,
      itemStatus: "WASTED",
      updatedAt: nowStamp(),
      version: it.version + 1,
    });
    return appendAudit(out, itemId, "USER", "removed", "ACTIVE", "WASTED");
  });
  pushToast(`${item.name} removed — no waste logged (entry mistakes path)`);
}

/** POST …/mark-exhausted (#8) — idempotent. Staples fire ItemRanOutEvent. */
export function markItemExhausted(itemId: string): void {
  const item = findInventoryItem(state, itemId);
  if (!item) return;
  if (item.itemStatus === "EXHAUSTED") return; // idempotent 200
  mutate((s) => {
    const it = findInventoryItem(s, itemId);
    if (!it) return s;
    const out = replaceItem(s, {
      ...it,
      itemStatus: "EXHAUSTED",
      updatedAt: nowStamp(),
      version: it.version + 1,
    });
    return appendAudit(out, itemId, "USER", "itemStatus", "ACTIVE", "EXHAUSTED");
  });
  pushToast(
    item.isStaple
      ? `${item.name} finished — it'll be added to your next shopping list`
      : `${item.name} marked finished`,
  );
}

/**
 * POST /provisions/meal-consumption (#10) — "Ate a portion" on BATCH_COOK
 * rows. Underflows floor at zero (HLD guardrail); nutrition logging is a
 * separate manual step on /nutrition (spec §9 Q5).
 */
export function consumePortions(itemId: string, portions: number): void {
  const item = findInventoryItem(state, itemId);
  if (!item || item.itemStatus !== "ACTIVE") {
    pushToast("404 — row gone elsewhere; re-fetched", "warn");
    return;
  }
  const available = item.quantity ?? 0;
  const next = Math.max(0, available - portions);
  mutate((s) => {
    const it = findInventoryItem(s, itemId);
    if (!it) return s;
    const out = replaceItem(s, {
      ...it,
      quantity: next,
      itemStatus: next === 0 ? "EXHAUSTED" : it.itemStatus,
      updatedAt: nowStamp(),
      version: it.version + 1,
    });
    return appendAudit(out, itemId, "USER", "quantity", available, next);
  });
  if (portions > available) {
    pushToast(
      `${portions} portions logged but only ${available} tracked — pantry floored at zero`,
      "warn",
    );
  } else if (next === 0) {
    pushToast("That was the last portion — log the meal on Nutrition if you ate it");
  } else {
    pushToast(`Portion deducted — ${next} left. Log the meal on Nutrition if you ate it`);
  }
}

/* ---- pantry: waste (#11–#13) ------------------------------------------------------------- */

let wasteSeq = 60;

/**
 * POST /provisions/waste (#11) — entries are IMMUTABLE (corrections append).
 * Linked entries deduct from the row server-side (floors at zero, may flip
 * WASTED). 422 when the quantity exceeds tracked remainder.
 */
export function logWaste(req: LogWasteRequest): boolean {
  const linked = req.inventoryItemId
    ? findInventoryItem(state, req.inventoryItemId)
    : undefined;
  if (
    linked &&
    linked.trackingMode === "QUANTITY" &&
    req.quantity != null &&
    req.unit === linked.unit &&
    req.quantity > (linked.quantity ?? 0)
  ) {
    pushToast(
      `422 — that's more than you have tracked (${linked.quantity ?? 0} ${linked.unit ?? ""} left)`,
      "warn",
    );
    return false;
  }
  mutate((s) => {
    const entry: WasteEntryDto = {
      id: `we-${++wasteSeq}`,
      userId: MOCK_USER_ID,
      inventoryItemId: req.inventoryItemId ?? null,
      itemName: req.itemName,
      quantity: req.quantity ?? null,
      unit: req.unit ?? null,
      reason: req.reason,
      costEstimate: req.costEstimate ?? null,
      occurredOn: req.occurredOn,
      notes: req.notes ?? null,
      createdAt: nowStamp(),
    };
    let out: StoreState = {
      ...s,
      pantry: { ...s.pantry, waste: [entry, ...s.pantry.waste] },
    };
    if (req.inventoryItemId && req.quantity != null) {
      const it = findInventoryItem(out, req.inventoryItemId);
      if (it && it.trackingMode === "QUANTITY") {
        const next = Math.max(0, (it.quantity ?? 0) - req.quantity);
        out = replaceItem(out, {
          ...it,
          quantity: next,
          itemStatus: next === 0 ? "WASTED" : it.itemStatus,
          updatedAt: nowStamp(),
          version: it.version + 1,
        });
        out = appendAudit(
          out,
          it.id,
          "USER",
          "quantity",
          it.quantity,
          next,
        );
      }
    }
    return out;
  });
  pushToast("Waste logged — entries can't be edited; log a correction if needed");
  return true;
}

/** GET /provisions/waste/summary equivalent (#13) — computed per range. */
export function wasteSummaryFor(
  s: StoreState,
  from: string,
  to: string,
): WasteSummaryDto {
  const rows = s.pantry.waste.filter(
    (w) => w.occurredOn >= from && w.occurredOn <= to,
  );
  const countByReason: Record<string, number> = {};
  const byItem = new Map<string, { entryCount: number; totalCost: number }>();
  let totalCost = 0;
  for (const w of rows) {
    countByReason[w.reason] = (countByReason[w.reason] ?? 0) + 1;
    totalCost += w.costEstimate ?? 0;
    const t = byItem.get(w.itemName) ?? { entryCount: 0, totalCost: 0 };
    byItem.set(w.itemName, {
      entryCount: t.entryCount + 1,
      totalCost: Math.round((t.totalCost + (w.costEstimate ?? 0)) * 100) / 100,
    });
  }
  const topItems = [...byItem.entries()]
    .map(([itemName, v]) => ({ itemName, ...v }))
    .sort((a, b) => b.totalCost - a.totalCost)
    .slice(0, 3);
  return {
    from,
    to,
    totalCostEstimate: Math.round(totalCost * 100) / 100,
    totalEntries: rows.length,
    countByReason,
    topItems,
  };
}

/* ---- pantry: equipment (#14–#16) ----------------------------------------------------------- */

let equipmentSeq = 20;

/** PUT /provisions/equipment/{name} (#15) — upsert (200 update / 201 insert). */
export function upsertEquipment(name: string, req: UpsertEquipmentRequest): boolean {
  if (!/^[a-z0-9_]+$/.test(name)) {
    pushToast("400 — equipment names are canonical snake_case (e.g. air_fryer)", "warn");
    return false;
  }
  const existing = state.pantry.equipment.find((e) => e.name === name);
  if (existing && req.expectedVersion != null && req.expectedVersion !== existing.version) {
    pushToast("409 — equipment changed elsewhere; reloaded", "warn");
    return false;
  }
  mutate((s) => {
    const cur = s.pantry.equipment.find((e) => e.name === name);
    const rows = cur
      ? s.pantry.equipment.map((e) =>
          e.name === name
            ? {
                ...e,
                available: req.available,
                details: req.details ?? null,
                version: e.version + 1,
              }
            : e,
        )
      : [
          ...s.pantry.equipment,
          {
            id: `eq-${++equipmentSeq}`,
            userId: MOCK_USER_ID,
            name,
            available: req.available,
            details: req.details ?? null,
            version: 1,
          },
        ];
    return { ...s, pantry: { ...s.pantry, equipment: rows } };
  });
  return true;
}

/** DELETE /provisions/equipment/{name} (#16) — 204; 404 = already gone. */
export function removeEquipment(name: string): void {
  mutate((s) => ({
    ...s,
    pantry: {
      ...s.pantry,
      equipment: s.pantry.equipment.filter((e) => e.name !== name),
    },
  }));
}

/* ---- pantry: budget (#17/#18) --------------------------------------------------------------- */

/**
 * PUT /provisions/budget (#18) — upsert; insert and update both 200. Currency
 * cannot change on an existing budget (422). The pantry BudgetDto is the
 * single record — budget is a provisions concern (preferences.md §7).
 */
export function saveBudget(req: UpdateBudgetRequest): boolean {
  const existing = state.pantry.budget;
  if (existing && req.currency !== existing.currency) {
    pushToast("422 — currency can't change on an existing budget", "warn");
    return false;
  }
  if (existing && req.expectedVersion !== existing.version) {
    pushToast("409 — budget changed elsewhere; reloaded", "warn");
    return false;
  }
  mutate((s) => ({
    ...s,
    pantry: {
      ...s.pantry,
      budget: {
        id: s.pantry.budget?.id ?? "budget-0001",
        userId: MOCK_USER_ID,
        weeklyTarget: req.weeklyTarget,
        currency: req.currency,
        toleranceOver: req.toleranceOver,
        priceSensitivity: req.priceSensitivity,
        enabled: req.enabled ?? true,
        spendTracking: null, // always null in v1
        version: (s.pantry.budget?.version ?? 0) + 1,
      },
    },
  }));
  pushToast("Budget saved — the planner optimises cost against it");
  return true;
}

/**
 * Mark a pantry item spoiled (#7, idempotent): the row leaves the ACTIVE
 * list and — when nothing is already pending — the planner raises a
 * contract-shaped re-opt suggestion (PROVISIONS listener, cross-page
 * liveliness). Spoiling does NOT log waste (spec §9 Q4) — `alsoLogWaste`
 * plays the confirm dialog's second, independent call.
 */
export function markSpoiled(id: string, alsoLogWaste = false): void {
  mutate((s) => {
    const item = s.pantry.items.find((it) => it.id === id);
    if (!item || item.itemStatus !== "ACTIVE") return s;

    let out: StoreState = replaceItem(s, {
      ...item,
      itemStatus: "SPOILED",
      updatedAt: nowStamp(),
      version: item.version + 1,
    });
    out = appendAudit(out, id, "USER", "itemStatus", "ACTIVE", "SPOILED");
    if (alsoLogWaste) {
      const entry: WasteEntryDto = {
        id: `we-${++wasteSeq}`,
        userId: MOCK_USER_ID,
        inventoryItemId: id,
        itemName: item.name,
        quantity: item.quantity ?? null,
        unit: item.unit ?? null,
        reason: "SPOILED_EARLY",
        costEstimate: item.costPaid ?? null,
        occurredOn: MOCK_TODAY_ISO,
        notes: "logged from mark-spoiled",
        createdAt: nowStamp(),
      };
      out = {
        ...out,
        pantry: { ...out.pantry, waste: [entry, ...out.pantry.waste] },
      };
    }

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
  if (LIVE) {
    liveMutation(
      apiSend("POST", `/api/v1/nutrition/intake/${date}/slots/${mealSlot}/confirm`),
      "Couldn't log the meal",
    );
    return;
  }
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
  if (LIVE) {
    liveMutation(
      apiSend("POST", `/api/v1/nutrition/intake/${date}/slots/${mealSlot}/skip`),
      "Couldn't skip the meal",
    );
    return;
  }
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
  if (LIVE) {
    liveMutation(
      apiSend("POST", `/api/v1/nutrition/intake/${date}/snacks`, req),
      "Couldn't log the snack",
    );
    return;
  }
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
 * satFat aggregate added by backend #247 (TargetsDto already carried a satFat
 * target). Slots have no per-slot satFat, so the fixture sources its actual
 * from microsActualSoFar["saturated_fat_g"] and leaves plannedG at 0; the stat
 * band still reads that micro directly (unchanged here).
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
  // satFat (#247) has no per-slot planned/actual; its actual is the
  // saturated_fat_g micro — the same value the stat band reads.
  const satFatActual = microsActual["saturated_fat_g"] ?? 0;
  return {
    caloriesPlanned,
    caloriesActualSoFar: caloriesActual,
    caloriesRemaining: remaining(targets.calories.dailyTarget, caloriesActual),
    protein: macro("protein", targets.protein.targetG),
    carbs: macro("carbs", targets.carbs.targetG),
    fat: macro("fat", targets.fat.targetG),
    fibre: macro("fibre", targets.fibre.targetG),
    satFat: {
      plannedG: 0,
      actualSoFarG: round1(satFatActual),
      remainingG: remaining(targets.satFat.targetG ?? 0, satFatActual),
    },
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
    satFat: {
      plannedG: sum((d) => d.satFat.plannedG),
      actualSoFarG: sum((d) => d.satFat.actualSoFarG),
      remainingG: 0,
    },
    microsActualSoFar: totalMicros,
  };
  // floorViolations is now FloorViolationDto[] (date/floor/actual per the
  // contract). The page still derives its per-day chips via
  // floorViolationDayIndices, so we surface one weekly-level entry (date: null)
  // per violated macro to keep that mapping 1:1. Hard-floor macros only — the
  // planner's multiplicative gate; micro hard floors are possible in the
  // contract but not simulated here.
  const floorViolations = FLOOR_MACROS.filter(
    (k) => floorViolationDayIndices(n, targets, k).length > 0,
  ).map((k) => ({
    date: null,
    macroOrMicro: k,
    floor: targets[k].floorG ?? 0,
    actual: weeklyTotal[k].actualSoFarG,
  }));
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

/**
 * The §3b status state machine, server-enforced (anything else → 409):
 * UNREAD → READ | ACTIONED | DISMISSED · READ → ACTIONED | DISMISSED ·
 * ACTIONED → DISMISSED · DISMISSED terminal.
 */
const LEGAL_TRANSITIONS: Record<
  MockNotificationDto["status"],
  MockNotificationDto["status"][]
> = {
  UNREAD: ["READ", "ACTIONED", "DISMISSED"],
  READ: ["ACTIONED", "DISMISSED"],
  ACTIONED: ["DISMISSED"],
  DISMISSED: [],
};

function transitionNotification(
  s: StoreState,
  id: string,
  next: MockNotificationDto["status"],
  /** §5: a 409 because the row is already past that state is swallowed. */
  swallow409: boolean,
): StoreState {
  const n = s.notifications.rows.find((r) => r.id === id);
  if (!n) {
    pushToast("404 — notification no longer exists", "warn");
    return s;
  }
  if (!LEGAL_TRANSITIONS[n.status].includes(next)) {
    if (!swallow409) {
      pushToast(`409 — already ${n.status.toLowerCase()}`, "warn");
    }
    return s; // silent re-fetch: the stored row already reflects the server
  }
  const stamped: MockNotificationDto = {
    ...n,
    status: next,
    readAt: next === "READ" ? nowIso() : n.readAt,
    actionedAt: next === "ACTIONED" ? nowIso() : n.actionedAt,
    dismissedAt: next === "DISMISSED" ? nowIso() : n.dismissedAt,
    version: n.version + 1,
  };
  return {
    ...s,
    notifications: {
      ...s.notifications,
      rows: s.notifications.rows.map((r) => (r.id === id ? stamped : r)),
    },
  };
}

/** POST /notifications/{id}/read (#4) — row click / explicit mark-read. */
export function markNotificationRead(id: string): void {
  mutate((s) => transitionNotification(s, id, "READ", true));
}

/** POST /notifications/{id}/action (#6) — fired before following the deep
 *  link; a 409 because the row was already ACTIONED is swallowed (§3b). */
export function actionNotification(id: string): void {
  mutate((s) => transitionNotification(s, id, "ACTIONED", true));
}

/** POST /notifications/{id}/dismiss (#5). */
export function dismissNotification(id: string): void {
  mutate((s) => transitionNotification(s, id, "DISMISSED", false));
}

/** POST /notifications/bulk/read (#7) — empty kinds = all kinds; only ever
 *  targets UNREAD rows server-side. Returns BulkReadResponse.updated. */
export function bulkMarkNotificationsRead(kinds: AnyNotificationKind[]): number {
  let updated = 0;
  mutate((s) => ({
    ...s,
    notifications: {
      ...s.notifications,
      rows: s.notifications.rows.map((n) => {
        if (n.status !== "UNREAD") return n;
        if (kinds.length > 0 && !kinds.includes(n.kind)) return n;
        updated += 1;
        return { ...n, status: "READ", readAt: nowIso(), version: n.version + 1 };
      }),
    },
  }));
  return updated;
}

/** GET /notifications/preferences (#9) — auto-seeds defaults on first open
 *  (idempotent), so the panel never has an empty state. */
export function loadNotificationPrefs(): void {
  if (state.notifications.prefs) return;
  mutate((s) => ({
    ...s,
    notifications: {
      ...s.notifications,
      prefs: {
        id: "ntf-pref-seeded",
        userId: MOCK_USER_ID,
        // PLANNER_PLAN_GENERATED seeds OFF; everything else ON (§3e).
        enabledKinds: Object.fromEntries(
          ALL_NOTIFICATION_KINDS.map((k) => [k, k !== "PLANNER_PLAN_GENERATED"]),
        ),
        quietHoursEnabled: true,
        quietHoursStart: "22:00",
        quietHoursEnd: "07:00",
        timezone: "Europe/London",
        debounceWindowMinutes: 30,
        version: 0,
      },
    },
  }));
}

export type SavePrefsOutcome = "ok" | "conflict" | "invalid";

/** PUT /notifications/preferences (#10) — a FULL replace. Always send the
 *  whole document incl. expectedVersion (the Java record binds primitives —
 *  omitted fields silently default and a missing version 409s; §8 Q5). */
export function saveNotificationPrefs(
  req: UpdateNotificationPreferenceRequest,
): SavePrefsOutcome {
  const cur = state.notifications.prefs;
  if (!cur) return "conflict";
  if (req.expectedVersion !== cur.version) {
    pushToast("409 — preferences changed elsewhere; review and save again", "warn");
    return "conflict";
  }
  // @ValidQuietHours: enabled-with-null-times → 400 (§3e).
  if (req.quietHoursEnabled && (!req.quietHoursStart || !req.quietHoursEnd)) {
    return "invalid";
  }
  if (
    req.debounceWindowMinutes != null &&
    (req.debounceWindowMinutes < 0 || req.debounceWindowMinutes > 360)
  ) {
    return "invalid";
  }
  mutate((s) => ({
    ...s,
    notifications: {
      ...s.notifications,
      prefs: {
        ...cur,
        enabledKinds: req.enabledKinds,
        quietHoursEnabled: req.quietHoursEnabled ?? false,
        quietHoursStart: req.quietHoursStart ?? null,
        quietHoursEnd: req.quietHoursEnd ?? null,
        timezone: req.timezone,
        debounceWindowMinutes: req.debounceWindowMinutes ?? 0,
        version: cur.version + 1,
      },
    },
  }));
  pushToast("Notification preferences saved");
  return "ok";
}

/** GET /notifications/summary (#2) — memoised per rows reference so the
 *  selector returns a stable object (useSyncExternalStore contract). */
let summaryCache: {
  rows: MockNotificationDto[];
  value: NotificationSummaryDto;
} | null = null;

export function selectNotificationSummary(s: StoreState): NotificationSummaryDto {
  const rows = s.notifications.rows;
  if (!summaryCache || summaryCache.rows !== rows) {
    const unread = rows.filter((n) => n.status === "UNREAD");
    summaryCache = {
      rows,
      value: {
        unreadCount: unread.length,
        attentionCount: unread.filter((n) => n.severity === "ATTENTION").length,
        urgentCount: unread.filter((n) => n.severity === "URGENT").length,
        generatedAt: nowIso(),
      },
    };
  }
  return summaryCache.value;
}

/* ---- preferences (preferences.md) -----------------------------------------------------------------
 * Contract shapes throughout. Taste profile is versioned with monotonic
 * rollback-as-replay; hard constraints carry the GAP-04 Tier-1 removal gate
 * (409 + Tier1RemovalConfirmationProblem → interstitial → re-submit with
 * confirmTier1Removals=true); lifestyle is a full-replacement PUT.
 */

let prefAuditSeq = 100;
let prefVersionRowSeq = 100;

const prefNorm = (v: string): string => v.trim().toLowerCase();

/** True after the one pending mock delta has been served (three-event rule —
 *  a manual refresh can legitimately change nothing thereafter, spec §3e). */
let refreshDeltaServed = false;

function tasteAuditRow(
  args: Omit<TasteProfileAuditEntryDto, "id" | "actorUserId" | "traceId" | "occurredAt">,
): TasteProfileAuditEntryDto {
  return {
    id: `tpa-r${++prefAuditSeq}`,
    actorUserId: MOCK_USER_ID,
    traceId: null,
    occurredAt: nowIso(),
    ...args,
  };
}

/**
 * POST /preferences/taste-profile/refresh-now (no body) — 202, async. The
 * UI polls #1 until the documentVersion bumps; there is no completion signal
 * and "no change" is a legitimate outcome (three-event rule, spec §8 Q2).
 */
export function refreshTasteProfile(): void {
  if (state.preferences.refreshing || !state.preferences.tasteProfile) return;
  mutate((s) => {
    const tp = s.preferences.tasteProfile;
    if (!tp) return s;
    return {
      ...s,
      preferences: {
        ...s.preferences,
        refreshing: true,
        tasteAudit: [
          tasteAuditRow({
            actorType: "USER",
            changeType: "REFRESH_TRIGGERED",
            previousDocumentVersion: tp.documentVersion,
            newDocumentVersion: tp.documentVersion,
            summary: "Manual refresh requested",
          }),
          ...s.preferences.tasteAudit,
        ],
      },
    };
  });
  setTimeout(() => {
    mutate((s) => {
      const tp = s.preferences.tasteProfile;
      if (!tp || !s.preferences.refreshing) return s;
      if (refreshDeltaServed) {
        // Nothing new agreed 2–3 times — the poll times out with no bump.
        return {
          ...s,
          preferences: { ...s.preferences, refreshing: false },
        };
      }
      refreshDeltaServed = true;
      const nextVersion = tp.documentVersion + 1;
      const ingredients = tp.document.ingredientPreferences ?? {};
      const nextDocument: TasteProfileDocument = {
        ...tp.document,
        version: nextVersion,
        lastUpdated: MOCK_TODAY_ISO,
        basedOnFeedbackCount: tp.basedOnFeedbackCount + 3,
        ingredientPreferences: {
          ...ingredients,
          favourites: [
            ...(ingredients.favourites ?? []),
            { item: "kimchi", evidenceCount: 3, lastSignal: MOCK_TODAY_ISO, source: "FEEDBACK" },
          ],
          trendingPositive: (ingredients.trendingPositive ?? []).filter(
            (t) => t.item !== "kimchi",
          ),
        },
      };
      const versionRow: TasteProfileVersionDto = {
        id: `tpv-r${++prefVersionRowSeq}`,
        tasteProfileId: tp.id,
        documentVersion: nextVersion,
        documentSnapshot: nextDocument,
        feedbackRangeStart: tp.feedbackCursor ?? null,
        feedbackRangeEnd: `fb-${feedbackSeq}`,
        trigger: "MANUAL",
        deltasApplied: [
          { op: "promote", path: "ingredientPreferences.favourites", item: "kimchi" },
        ],
        modelTierUsed: "MID",
        generatedAt: nowIso(),
      };
      return pushNotification(
        {
          ...s,
          preferences: {
            ...s.preferences,
            refreshing: false,
            tasteProfile: {
              ...tp,
              document: nextDocument,
              documentVersion: nextVersion,
              basedOnFeedbackCount: tp.basedOnFeedbackCount + 3,
              lastDeltaAppliedAt: nowIso(),
              optimisticVersion: tp.optimisticVersion + 1,
              updatedAt: nowIso(),
            },
            versions: [versionRow, ...s.preferences.versions],
            tasteAudit: [
              tasteAuditRow({
                actorType: "AI",
                changeType: "AI_DELTA_APPLIED",
                previousDocumentVersion: tp.documentVersion,
                newDocumentVersion: nextVersion,
                summary: "Promoted kimchi to favourites — 3 repeated signals",
              }),
              ...s.preferences.tasteAudit,
            ],
          },
        },
        "ai",
        `Taste profile refreshed — v${nextVersion} built from 3 new feedback signals`,
      );
    });
  }, 1500);
}

/** PUT /preferences/taste-profile — manual override (full replacement). */
export function saveTasteProfile(
  document: TasteProfileDocument,
  expectedVersion: number,
): "ok" | "conflict" {
  const tp = state.preferences.tasteProfile;
  if (!tp) return "conflict";
  if (expectedVersion !== tp.optimisticVersion) {
    pushToast("409 — profile changed since you opened the editor", "warn");
    return "conflict";
  }
  mutate((s) => {
    const cur = s.preferences.tasteProfile;
    if (!cur) return s;
    const nextVersion = cur.documentVersion + 1;
    // Server-managed scalars are re-stamped, never trusted (spec §8 Q1).
    const nextDocument: TasteProfileDocument = {
      ...document,
      version: nextVersion,
      lastUpdated: MOCK_TODAY_ISO,
      basedOnFeedbackCount: cur.basedOnFeedbackCount,
      feedbackCursor: cur.feedbackCursor ?? null,
    };
    const versionRow: TasteProfileVersionDto = {
      id: `tpv-r${++prefVersionRowSeq}`,
      tasteProfileId: cur.id,
      documentVersion: nextVersion,
      documentSnapshot: nextDocument,
      feedbackRangeStart: null,
      feedbackRangeEnd: null,
      trigger: "MANUAL",
      deltasApplied: [{ op: "manual_override" }],
      modelTierUsed: "NONE",
      generatedAt: nowIso(),
    };
    return {
      ...s,
      preferences: {
        ...s.preferences,
        tasteProfile: {
          ...cur,
          document: nextDocument,
          documentVersion: nextVersion,
          optimisticVersion: cur.optimisticVersion + 1,
          updatedAt: nowIso(),
        },
        versions: [versionRow, ...s.preferences.versions],
        tasteAudit: [
          tasteAuditRow({
            actorType: "USER",
            changeType: "MANUAL_OVERRIDE",
            previousDocumentVersion: cur.documentVersion,
            newDocumentVersion: nextVersion,
            summary: "Manual override — flagged so the advisor won't re-learn it",
          }),
          ...s.preferences.tasteAudit,
        ],
      },
    };
  });
  pushToast("Saved. The advisor won't re-learn this from old feedback.");
  return "ok";
}

/**
 * POST /preferences/taste-profile/rollback — restores the target snapshot as
 * a NEW monotonic version and replays later feedback (never a decrement).
 */
export function rollbackTasteProfile(
  targetDocumentVersion: number,
  expectedVersion: number,
): "ok" | "conflict" | "missing" {
  const tp = state.preferences.tasteProfile;
  if (!tp) return "missing";
  const target = state.preferences.versions.find(
    (v) => v.documentVersion === targetDocumentVersion,
  );
  if (!target) {
    pushToast("404 — that snapshot is no longer available", "warn");
    return "missing";
  }
  if (expectedVersion !== tp.optimisticVersion) {
    pushToast("409 — profile changed since you opened the drawer", "warn");
    return "conflict";
  }
  mutate((s) => {
    const cur = s.preferences.tasteProfile;
    if (!cur) return s;
    const nextVersion = cur.documentVersion + 1;
    const nextDocument: TasteProfileDocument = {
      ...target.documentSnapshot,
      version: nextVersion,
      lastUpdated: MOCK_TODAY_ISO,
    };
    const versionRow: TasteProfileVersionDto = {
      id: `tpv-r${++prefVersionRowSeq}`,
      tasteProfileId: cur.id,
      documentVersion: nextVersion,
      documentSnapshot: nextDocument,
      feedbackRangeStart: target.feedbackRangeEnd ?? null,
      feedbackRangeEnd: cur.feedbackCursor ?? null,
      trigger: "MANUAL",
      deltasApplied: [
        { op: "rollback_replay", replayedFromVersion: targetDocumentVersion },
      ],
      modelTierUsed: "MID",
      generatedAt: nowIso(),
    };
    return pushNotification(
      {
        ...s,
        preferences: {
          ...s.preferences,
          tasteProfile: {
            ...cur,
            document: nextDocument,
            documentVersion: nextVersion,
            optimisticVersion: cur.optimisticVersion + 1,
            updatedAt: nowIso(),
          },
          versions: [versionRow, ...s.preferences.versions],
          tasteAudit: [
            tasteAuditRow({
              actorType: "USER",
              changeType: "ROLLED_BACK",
              previousDocumentVersion: cur.documentVersion,
              newDocumentVersion: nextVersion,
              summary: `Restored v${targetDocumentVersion} as v${nextVersion}; feedback given since then re-applied`,
            }),
            ...s.preferences.tasteAudit,
          ],
        },
      },
      "ai",
      `Taste profile restored — v${targetDocumentVersion} replayed forward as v${nextVersion}`,
    );
  });
  return "ok";
}

/* ---- hard constraints + the GAP-04 Tier-1 removal gate ---- */

/** Excluded-food sets for comparable bases; keto/paleo/other are incomparable. */
const BASE_EXCLUSIONS: Record<string, readonly string[]> = {
  omnivore: [],
  pescatarian: ["meat"],
  vegetarian: ["meat", "fish"],
  vegan: ["meat", "fish", "dairy", "eggs"],
};

/** Base RELAXATION only — the new excluded set is a strict subset (§4b). */
function isBaseRelaxation(oldBase: string, newBase: string): boolean {
  const prev = BASE_EXCLUSIONS[prefNorm(oldBase)];
  const next = BASE_EXCLUSIONS[prefNorm(newBase)];
  if (!prev || !next) return false;
  return next.length < prev.length && next.every((x) => prev.includes(x));
}

/** Pure stored-vs-request diff (case-insensitive + trimmed) — the detector. */
function detectTier1Removals(
  stored: NonNullable<StoreState["preferences"]["hardConstraints"]>,
  req: UpdateHardConstraintsRequest,
): RemovedTier1Constraint[] {
  const out: RemovedTier1Constraint[] = [];
  const reqAllergies = req.allergies.map(prefNorm);
  for (const a of stored.allergies) {
    if (!reqAllergies.includes(prefNorm(a))) out.push({ category: "ALLERGY", value: a });
  }
  const reqDiets = req.medicalDiets.map(prefNorm);
  for (const d of stored.medicalDiets) {
    if (!reqDiets.includes(prefNorm(d))) out.push({ category: "MEDICAL_DIET", value: d });
  }
  // Substance removal only — editing a kept substance's severity/notes is fine.
  const reqSubstances = req.intolerances.map((i) => prefNorm(i.substance));
  for (const i of stored.intolerances) {
    if (!reqSubstances.includes(prefNorm(i.substance))) {
      out.push({ category: "SEVERE_INTOLERANCE", value: i.substance });
    }
  }
  if (
    prefNorm(req.dietaryIdentity.base) !== prefNorm(stored.dietaryIdentity.base) &&
    isBaseRelaxation(stored.dietaryIdentity.base, req.dietaryIdentity.base)
  ) {
    out.push({ category: "DIETARY_IDENTITY_BASE", value: stored.dietaryIdentity.base });
  }
  return out;
}

export type SaveHardConstraintsResult =
  | { kind: "ok" }
  | { kind: "conflict" }
  | { kind: "tier1"; problem: Tier1RemovalConfirmationProblem };

/**
 * PUT /preferences/hard-constraints. Two distinct 409s on this route:
 * optimistic-lock vs the GAP-04 Tier1RemovalConfirmationProblem — the page
 * matches `reason === "TIER1_REMOVAL_REQUIRES_CONFIRMATION"` (spec §4b).
 */
export function saveHardConstraints(
  req: UpdateHardConstraintsRequest,
): SaveHardConstraintsResult {
  const stored = state.preferences.hardConstraints;
  if (!stored) return { kind: "conflict" };
  if (req.expectedVersion !== stored.version) {
    pushToast("409 — constraints changed since you opened this", "warn");
    return { kind: "conflict" };
  }
  const removed = detectTier1Removals(stored, req);
  if (removed.length > 0 && req.confirmTier1Removals !== true) {
    // Rejected wholesale: no mutation, no audit row, no version bump.
    return {
      kind: "tier1",
      problem: {
        type: "https://mealprep.example.com/problems/tier1-removal-requires-confirmation",
        title: "Tier-1 hard-constraint removal requires confirmation",
        status: 409,
        detail:
          "The request removes safety-critical constraints; re-submit with confirmTier1Removals=true to proceed.",
        reason: "TIER1_REMOVAL_REQUIRES_CONFIRMATION",
        removedConstraints: removed,
      },
    };
  }
  mutate((s) => {
    const cur = s.preferences.hardConstraints;
    if (!cur) return s;
    const fields = [
      ["allergies", cur.allergies, req.allergies],
      ["medicalDiets", cur.medicalDiets, req.medicalDiets],
      ["dietaryIdentity", cur.dietaryIdentity, req.dietaryIdentity],
      ["intolerances", cur.intolerances, req.intolerances],
    ] as const;
    const auditRows: HardConstraintsAuditEntryDto[] = fields
      .filter(([, prev, next]) => JSON.stringify(prev) !== JSON.stringify(next))
      .map(([field, prev, next]) => ({
        id: `hca-r${++prefAuditSeq}`,
        hardConstraintsId: cur.id,
        actorUserId: MOCK_USER_ID,
        fieldChanged: field,
        previousValueJson: prev,
        newValueJson: next,
        occurredAt: nowIso(),
      }));
    const out: StoreState = {
      ...s,
      preferences: {
        ...s.preferences,
        hardConstraints: {
          ...cur,
          allergies: req.allergies,
          medicalDiets: req.medicalDiets,
          dietaryIdentity: req.dietaryIdentity,
          intolerances: req.intolerances,
          // ageRestrictions are echoed back unchanged — auto-managed (§4a).
          version: cur.version + 1,
        },
        hardAudit: [...auditRows, ...s.preferences.hardAudit],
      },
    };
    return removed.length > 0
      ? pushNotification(
          out,
          "ai",
          `Safety filter updated — ${removed
            .map((r) => r.value.toLowerCase())
            .join(", ")} removed after confirmation`,
        )
      : out;
  });
  pushToast("Hard constraints saved — the safety filter applies immediately.");
  return { kind: "ok" };
}

/* ---- lifestyle config ---- */

/** POST /preferences/lifestyle-config/mark-reviewed (§5a review nudge). */
export function markLifestyleReviewed(): void {
  mutate((s) =>
    s.preferences.lifestyle
      ? {
          ...s,
          preferences: {
            ...s.preferences,
            lifestyle: { ...s.preferences.lifestyle, lastReviewPromptAt: null },
          },
        }
      : s,
  );
  pushToast("Marked as still accurate — next nudge in 2–3 months.");
}

/** PUT /preferences/lifestyle-config — full document replacement. */
export function saveLifestyleConfig(
  req: UpdateLifestyleConfigRequest,
): "ok" | "conflict" {
  const cur = state.preferences.lifestyle;
  if (!cur) return "conflict";
  if (req.expectedVersion !== cur.optimisticVersion) {
    pushToast("409 — lifestyle config changed since you opened this", "warn");
    return "conflict";
  }
  mutate((s) => {
    const stored = s.preferences.lifestyle;
    if (!stored) return s;
    const keys = new Set([
      ...Object.keys(stored.document),
      ...Object.keys(req.document),
    ]) as Set<keyof typeof stored.document>;
    const auditRows: LifestyleConfigAuditEntryDto[] = [...keys]
      .filter(
        (k) =>
          JSON.stringify(stored.document[k] ?? null) !==
          JSON.stringify(req.document[k] ?? null),
      )
      .map((k) => ({
        id: `lca-r${++prefAuditSeq}`,
        actorUserId: MOCK_USER_ID,
        fieldPath: k,
        previousValueJson: stored.document[k] ?? null,
        newValueJson: req.document[k] ?? null,
        occurredAt: nowIso(),
      }));
    return {
      ...s,
      preferences: {
        ...s.preferences,
        lifestyle: {
          ...stored,
          document: req.document,
          optimisticVersion: stored.optimisticVersion + 1,
          updatedAt: nowIso(),
        },
        lifestyleAudit: [...auditRows, ...s.preferences.lifestyleAudit],
      },
    };
  });
  pushToast("Lifestyle config saved.");
  return "ok";
}

/* ---- cross-page compatibility helpers ---- */

/** Archive panel badge — GET /preferences/archive/active-count (#16). */
export function selectArchiveActiveCount(s: StoreState): number {
  return s.preferences.archive.filter((a) => a.rePromotedAt == null).length;
}

/** Start-of-window slot times from mealTiming.preferredSchedule ("08:00-08:30").
 *  Memoised per lifestyle reference — useStore selectors must return stable
 *  references (useSyncExternalStore re-render contract). */
let slotTimesCache: {
  source: StoreState["preferences"]["lifestyle"];
  value: Record<MealSlotKey, string>;
} | null = null;

export function selectSlotTimes(s: StoreState): Record<MealSlotKey, string> {
  const source = s.preferences.lifestyle;
  if (!slotTimesCache || slotTimesCache.source !== source) {
    const times = source?.document.mealTiming?.preferredSchedule?.times ?? {};
    const startOf = (slot: MealSlotKey, fallback: string): string =>
      (times[slot] ?? fallback).split("-")[0];
    slotTimesCache = {
      source,
      value: {
        breakfast: startOf("breakfast", "08:00"),
        lunch: startOf("lunch", "13:00"),
        dinner: startOf("dinner", "19:00"),
      },
    };
  }
  return slotTimesCache.value;
}

/** Onboarding write — adds straight to hard-constraint allergies (no gate:
 *  GAP-04 gates removals only; additions stay one-step). */
export function addAllergy(name: string): void {
  const trimmed = name.trim();
  if (!trimmed) return;
  mutate((s) => {
    const cur = s.preferences.hardConstraints;
    if (!cur || cur.allergies.some((a) => prefNorm(a) === prefNorm(trimmed))) {
      return s;
    }
    return {
      ...s,
      preferences: {
        ...s.preferences,
        hardConstraints: {
          ...cur,
          allergies: [...cur.allergies, trimmed],
          version: cur.version + 1,
        },
        hardAudit: [
          {
            id: `hca-r${++prefAuditSeq}`,
            hardConstraintsId: cur.id,
            actorUserId: MOCK_USER_ID,
            fieldChanged: "allergies",
            previousValueJson: cur.allergies,
            newValueJson: [...cur.allergies, trimmed],
            occurredAt: nowIso(),
          },
          ...s.preferences.hardAudit,
        ],
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
 * Nudge a lifestyle slot window ±15 min (onboarding stepper). Today's
 * timeline does NOT mirror it: per the serve-time contract the lifestyle
 * fallback resolution is server-internal (plan.md §8 Q3).
 */
export function adjustSlotTime(slot: MealSlotKey, direction: 1 | -1): void {
  mutate((s) => {
    const cfg = s.preferences.lifestyle;
    if (!cfg) return s;
    const times = cfg.document.mealTiming?.preferredSchedule?.times ?? {};
    const range = times[slot] ?? "12:00-12:30";
    const shifted = range.split("-").map((t) => {
      const [h, m] = t.split(":").map(Number);
      return formatTime(
        Math.max(5 * 60, Math.min(23 * 60, h * 60 + m + direction * 15)),
      );
    });
    return {
      ...s,
      preferences: {
        ...s.preferences,
        lifestyle: {
          ...cfg,
          document: {
            ...cfg.document,
            mealTiming: {
              ...cfg.document.mealTiming,
              preferredSchedule: {
                ...cfg.document.mealTiming?.preferredSchedule,
                times: { ...times, [slot]: shifted.join("-") },
              },
            },
          },
          optimisticVersion: cfg.optimisticVersion + 1,
          updatedAt: nowIso(),
        },
      },
    };
  });
}

/** Nudge the weekly budget ±£5 — budget is a provisions concern (the pantry
 *  BudgetDto is the system of record; preferences.md §7). */
export function adjustWeeklyBudget(direction: 1 | -1): void {
  mutate((s) => {
    if (!s.pantry.budget) return s;
    const budget = Math.max(
      25,
      Math.min(120, s.pantry.budget.weeklyTarget + direction * 5),
    );
    if (budget === s.pantry.budget.weeklyTarget) return s;
    return {
      ...s,
      pantry: {
        ...s.pantry,
        budget: {
          ...s.pantry.budget,
          weeklyTarget: budget,
          version: s.pantry.budget.version + 1,
        },
      },
    };
  });
}

/* ---- activity / feedback (activity.md) ----------------------------------------------------------
 * Contract shapes throughout. Tier marks render from the SERVER decision
 * (AUTO_ROUTED / ROUTED_WITH_FLAG); <0.5 produces no route row at all — the
 * whole entry pauses as CLARIFICATION_PENDING with an inbox query (§4b).
 */

/** Confidence tiers: ≥0.8 routed · 0.5–0.8 check me · <0.5 needs you.
 *  FALLBACK ONLY — prefer tierForDecision (the tier is server-decided). */
export function tierFor(conf: number): ConfidenceTier {
  if (conf >= 0.8) return "high";
  if (conf >= 0.5) return "mid";
  return "low";
}

/** The contract signal → display tier (activity.md §4b). */
export function tierForDecision(decision: RoutingDecision): ConfidenceTier {
  if (decision === "AUTO_ROUTED") return "high";
  if (decision === "ROUTED_WITH_FLAG") return "mid";
  return "low";
}

export const DESTINATION_LABEL: Record<Destination, string> = {
  RECIPE: "Recipe",
  PREFERENCE: "Preference",
  NUTRITION: "Nutrition",
  PROVISIONS: "Provisions",
};

let feedbackSeq = 310;
let routeSeq = 600;
let clarificationSeq = 410;
let correctionSeq = 510;

function findFeedback(s: StoreState, id: string): FeedbackEntryDto | undefined {
  return s.activity.feedback.find((f) => f.id === id);
}

function replaceFeedback(s: StoreState, next: FeedbackEntryDto): StoreState {
  return {
    ...s,
    activity: {
      ...s.activity,
      feedback: s.activity.feedback.map((f) => (f.id === next.id ? next : f)),
    },
  };
}

export function findClarification(
  s: StoreState,
  queryId: string,
): ClarificationQueryDto | undefined {
  return s.activity.clarifications.find((c) => c.id === queryId);
}

const AMBIGUOUS_FEEDBACK = /salty|salt|portion|more veg/i;

/** Mock classifier outcome for an unambiguous text: one confident route. */
function autoRoute(text: string): RoutingDecisionDto {
  return {
    id: `rt-${++routeSeq}`,
    destination: "PREFERENCE",
    confidence: 0.85,
    decision: "AUTO_ROUTED",
    status: "APPLIED",
    extractedFeedback: text.slice(0, 80),
    actionTaken:
      "Noted as a general preference — your taste profile weighs this from the next plan",
    destinationResult: null,
    failureMessage: null,
  };
}

/**
 * POST /api/v1/feedback (202 + Location) — the global modal's submit. The
 * entry lands non-terminal and the pages poll until classification settles:
 * either routes appear, or the whole entry pauses on a clarification.
 */
export function submitFeedback(text: string, context?: UiContextDto): string {
  const id = `fb-${++feedbackSeq}`;
  const entry: FeedbackEntryDto = {
    id,
    userId: MOCK_USER_ID,
    text,
    context: context ?? { screen: "GENERAL" },
    submissionStatus: "CLASSIFYING",
    classificationAttempts: 0,
    lastClassifiedAt: null,
    traceId: `trace-${id}`,
    routes: [],
    pendingClarificationQueryId: null,
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
  mutate((s) => ({
    ...s,
    activity: { ...s.activity, feedback: [entry, ...s.activity.feedback] },
  }));
  setTimeout(() => {
    mutate((s) => {
      const cur = findFeedback(s, id);
      if (!cur || cur.submissionStatus !== "CLASSIFYING") return s;
      if (AMBIGUOUS_FEEDBACK.test(cur.text)) {
        // A fragment dipped below 0.5 — no partial routing, the entry pauses.
        const saltY = /salty|salt/i.test(cur.text);
        const queryId = `cq-${++clarificationSeq}`;
        const query: ClarificationQueryDto = {
          id: queryId,
          feedbackEntryId: id,
          textExcerpt: cur.text.slice(0, 120),
          questionText: saltY
            ? "Is “too salty” about this one dish, or do you generally prefer less salt?"
            : "Is that about the plan's portions, or a daily nutrition target?",
          options: saltY
            ? [
                {
                  destination: "RECIPE",
                  snippet: "too salty",
                  classifierJustification:
                    "One-dish fix — propose a lower-salt version of that recipe",
                },
                {
                  destination: "PREFERENCE",
                  snippet: "too salty",
                  classifierJustification: "General lean — less salt everywhere",
                },
              ]
            : [
                {
                  destination: "NUTRITION",
                  snippet: "portions",
                  classifierJustification: "Could be a per-meal target change",
                },
                {
                  destination: "PREFERENCE",
                  snippet: "portions",
                  classifierJustification: "Could be a portion-style lean",
                },
              ],
          status: "PENDING",
          expiresAt: addDaysIso(MOCK_TODAY_ISO, 3) + "T12:00:00Z",
          createdAt: nowIso(),
        };
        const paused = replaceFeedback(s, {
          ...cur,
          submissionStatus: "CLARIFICATION_PENDING",
          classificationAttempts: 1,
          lastClassifiedAt: nowIso(),
          pendingClarificationQueryId: queryId,
          updatedAt: nowIso(),
        });
        return pushNotification(
          {
            ...paused,
            activity: {
              ...paused.activity,
              clarifications: [query, ...paused.activity.clarifications],
            },
          },
          "ai",
          "Your feedback needs one answer before it routes — check Activity",
        );
      }
      return pushNotification(
        replaceFeedback(s, {
          ...cur,
          submissionStatus: "ROUTED",
          classificationAttempts: 1,
          lastClassifiedAt: nowIso(),
          routes: [autoRoute(cur.text)],
          updatedAt: nowIso(),
        }),
        "ai",
        "Feedback routed to 1 destination",
      );
    });
  }, 1200);
  return id;
}

/**
 * POST /feedback/{feedbackId}/routes/{routingId}/correct — re-route a
 * misclassified fragment. One correction per route; the replay runs
 * synchronously (original row flips CORRECTED_AWAY, a replay row appears).
 */
export function correctRoute(
  feedbackId: string,
  routingId: string,
  newDestination: Destination,
  note?: string,
): void {
  const entry = state.activity.feedback.find((f) => f.id === feedbackId);
  const route = entry?.routes.find((r) => r.id === routingId);
  if (!entry || !route) {
    pushToast("404 — that route is gone; refreshed", "warn");
    return;
  }
  if (route.status === "CORRECTED_AWAY") {
    pushToast("422 — already corrected; submit fresh feedback instead", "warn");
    return;
  }
  if (newDestination === route.destination) {
    pushToast("422 — that's where it already went", "warn");
    return;
  }
  if (newDestination === "RECIPE" && !entry.context.recipeId) {
    pushToast("422 — no recipe attached to this feedback", "warn");
    return;
  }
  // Undo of the original write is best-effort (HLD correction limitations).
  const originalApplied = route.status === "APPLIED";
  mutate((s) => {
    const cur = findFeedback(s, feedbackId);
    if (!cur) return s;
    const replay: RoutingDecisionDto = {
      id: `rt-${++routeSeq}`,
      destination: newDestination,
      confidence: 0.99,
      decision: "AUTO_ROUTED",
      status: "APPLIED",
      extractedFeedback: route.extractedFeedback,
      actionTaken: `Re-routed by your correction — applied to ${DESTINATION_LABEL[newDestination].toLowerCase()}`,
      destinationResult: null,
      failureMessage: null,
    };
    const correction: MisclassificationCorrectionDto = {
      id: `corr-${++correctionSeq}`,
      feedbackEntryId: feedbackId,
      textExcerpt: cur.text.slice(0, 120),
      originalRoutingId: routingId,
      correctedDestination: newDestination,
      originalDestination: route.destination,
      originalConfidence: route.confidence,
      userCorrectionNote: note?.trim() ? note.trim().slice(0, 512) : null,
      actorUserId: MOCK_USER_ID,
      replayRoutingId: replay.id,
      replayStatus: "APPLIED",
      occurredAt: nowIso(),
      createdAt: nowIso(),
    };
    const next = replaceFeedback(s, {
      ...cur,
      submissionStatus: "CORRECTED",
      routes: [
        ...cur.routes.map((r) =>
          r.id === routingId ? { ...r, status: "CORRECTED_AWAY" as const } : r,
        ),
        replay,
      ],
      updatedAt: nowIso(),
    });
    return pushNotification(
      {
        ...next,
        activity: {
          ...next.activity,
          corrections: [correction, ...s.activity.corrections],
        },
      },
      "ai",
      "Routing correction recorded — logged as ground truth for the classifier",
    );
  });
  if (originalApplied) {
    pushToast("Previous action kept (undo is best-effort); routing corrected.");
  }
}

export type AnswerClarificationOutcome = "ok" | "gone" | "invalid" | "missing";

/**
 * POST /feedback/clarifications/{queryId}/answer — ≥1 of selectedDestination
 * / userClarificationText required (400). 200 is a RECEIVED receipt with no
 * routes: re-classification is queued, the entry re-enters the poll loop.
 */
export function answerClarification(
  queryId: string,
  req: AnswerClarificationRequest,
): AnswerClarificationOutcome {
  const query = state.activity.clarifications.find((c) => c.id === queryId);
  if (!query) return "missing";
  if (query.status === "EXPIRED") return "gone";
  if (query.status === "ANSWERED") {
    pushToast("422 — already answered; refreshed", "warn");
    return "invalid";
  }
  const dest = req.selectedDestination ?? null;
  const freeText = req.userClarificationText?.trim() || null;
  if (!dest && !freeText) return "invalid";
  mutate((s) => {
    const entry = findFeedback(s, query.feedbackEntryId);
    let out: StoreState = {
      ...s,
      activity: {
        ...s.activity,
        clarifications: s.activity.clarifications.map((c) =>
          c.id === queryId ? { ...c, status: "ANSWERED" as const } : c,
        ),
      },
    };
    if (entry) {
      out = replaceFeedback(out, {
        ...entry,
        submissionStatus: "RECEIVED",
        pendingClarificationQueryId: null,
        updatedAt: nowIso(),
      });
    }
    return out;
  });
  setTimeout(() => {
    mutate((s) => {
      const entry = findFeedback(s, query.feedbackEntryId);
      if (!entry || entry.submissionStatus !== "RECEIVED") return s;
      const salty = /salty|salt/i.test(entry.text);
      const answeredDest = dest ?? "PREFERENCE";
      const answered: RoutingDecisionDto = {
        id: `rt-${++routeSeq}`,
        destination: answeredDest,
        confidence: 0.97,
        decision: "AUTO_ROUTED",
        status: "APPLIED",
        extractedFeedback: query.options.find((o) => o.destination === dest)?.snippet ?? entry.text.slice(0, 80),
        actionTaken: freeText
          ? `Clarified in your words — “${freeText.slice(0, 120)}” applied to ${DESTINATION_LABEL[answeredDest].toLowerCase()}`
          : `Clarification answered — applied to ${DESTINATION_LABEL[answeredDest].toLowerCase()}`,
        destinationResult: null,
        failureMessage: null,
      };
      const routes: RoutingDecisionDto[] = salty
        ? [
            {
              id: `rt-${++routeSeq}`,
              destination: "RECIPE",
              confidence: 0.92,
              decision: "AUTO_ROUTED",
              status: "AWAITING_USER_APPROVAL",
              extractedFeedback: "way too salty",
              actionTaken:
                "Proposed adaptation — reduce the dominant salt source (awaiting your approval)",
              destinationResult: null,
              failureMessage: null,
            },
            {
              id: `rt-${++routeSeq}`,
              destination: "NUTRITION",
              confidence: 0.71,
              decision: "ROUTED_WITH_FLAG",
              status: "APPLIED",
              extractedFeedback: "portions have been small",
              actionTaken: "Increased per-meal dinner targets",
              destinationResult: null,
              failureMessage: null,
            },
            answered,
          ]
        : [answered];
      return pushNotification(
        replaceFeedback(s, {
          ...entry,
          submissionStatus: "ROUTED",
          classificationAttempts: entry.classificationAttempts + 1,
          lastClassifiedAt: nowIso(),
          routes,
          updatedAt: nowIso(),
        }),
        "ai",
        `Re-classified after your answer — routed to ${routes.length} destination${routes.length === 1 ? "" : "s"}`,
      );
    });
  }, 1500);
  return "ok";
}

/** 410-expired re-submit CTA → pre-fill the global feedback modal (§5b). */
export function requestComposePrefill(text: string): void {
  mutate((s) => ({
    ...s,
    activity: { ...s.activity, composePrefill: text },
  }));
}

export function clearComposePrefill(): void {
  mutate((s) =>
    s.activity.composePrefill === null
      ? s
      : { ...s, activity: { ...s.activity, composePrefill: null } },
  );
}

/* ---- household ------------------------------------------------------------------------------------------ */

/* ---- household / settings (settings.md) -----------------------------------------------------
 * Contract shapes throughout. Primary-only writes are render-gated in the UI;
 * the mock still enforces last-primary invariants (409) and optimistic
 * versions so stale saves surface the way the live API would.
 */

let inviteSeq = 2002;
let settingsAuditSeq = 100;

/** Derived invite status — never persisted (contract note on InviteStatus). */
export function inviteStatus(inv: HouseholdInviteDto): HouseholdInviteDto["status"] {
  if (inv.revokedAt) return "REVOKED";
  if (inv.acceptedAt) return "ACCEPTED";
  if (Date.parse(inv.expiresAt) < Date.parse(nowIso())) return "EXPIRED";
  return "PENDING";
}

/** POST /households (#2) — 409 when the caller is already in one (v1: one
 *  household per user). Returns false on the 409 so callers can advance. */
export function createHousehold(name: string): boolean {
  const trimmed = name.trim();
  if (!trimmed) return false;
  if (state.session.freshSetup) {
    markFreshStep("household");
    return true;
  }
  if (state.household.current) {
    pushToast("409 — you're already in a household", "warn");
    return false;
  }
  const me = state.session.user;
  const hhId = `hh-${Date.now()}`;
  mutate((s) => {
    const household = {
      id: hhId,
      name: trimmed,
      createdByUserId: me?.userId ?? MOCK_USER_ID,
      createdAt: nowIso(),
      version: 0,
      members: [
        {
          id: `m-${Date.now()}`,
          householdId: hhId,
          userId: me?.userId ?? MOCK_USER_ID,
          role: "primary" as const,
          displayName: me?.username ?? null,
          priority: 100,
          joinedAt: nowIso(),
          version: 0,
        },
      ],
    };
    const settings = {
      id: `hhs-${Date.now()}`,
      householdId: household.id,
      document: {
        slotDefaults: {
          breakfast: { shared: false, headcount: null, timeBudgetMin: null },
          lunch: { shared: false, headcount: null, timeBudgetMin: null },
          dinner: { shared: true, headcount: null, timeBudgetMin: null },
          snack: { shared: false, headcount: null, timeBudgetMin: null },
        },
        customSlots: [],
        defaultHeadcount: 1,
      },
      version: 0,
      createdAt: nowIso(),
    };
    return {
      ...s,
      household: {
        ...s.household,
        current: household,
        settings,
        resolved: resolveSlotConfiguration(household, settings),
      },
    };
  });
  return true;
}

/** POST /households/current/invites (#8) — the 201 response is the ONLY
 *  carrier of the invite code; the stored list row redacts it (§3b). */
export function createInvite(req: CreateInviteRequest): HouseholdInviteDto | null {
  const household = state.household.current;
  if (!household) return null;
  const id = `inv-${++inviteSeq}`;
  // Server caps expiry at now+30d and silently truncates (§8 Q5) — the UI
  // should echo the returned expiresAt, not the requested one.
  const capMs = Date.parse(nowIso()) + 30 * 86_400_000;
  const expiresAt = new Date(
    Math.min(Date.parse(req.expiresAt), capMs),
  ).toISOString();
  const code = `MP-${id.toUpperCase().replace("INV-", "")}-${Math.random()
    .toString(36)
    .slice(2, 6)
    .toUpperCase()}`;
  const stored: HouseholdInviteDto = {
    id,
    householdId: household.id,
    inviteCode: null,
    issuedByUserId: state.session.user?.userId ?? MOCK_USER_ID,
    issuedForUserId: req.issuedForUserId ?? null,
    intendedRole: req.intendedRole,
    expiresAt,
    acceptedAt: null,
    revokedAt: null,
    status: "PENDING",
  };
  mutate((s) => ({
    ...s,
    household: {
      ...s.household,
      invites: [stored, ...s.household.invites],
      inviteCodes: { ...s.household.inviteCodes, [id]: code },
    },
  }));
  return { ...stored, inviteCode: code };
}

/** DELETE /households/current/invites/{id} (#9) — 409 if already resolved. */
export function revokeInvite(inviteId: string): void {
  mutate((s) => {
    const inv = s.household.invites.find((i) => i.id === inviteId);
    if (!inv) {
      pushToast("404 — invite no longer exists", "warn");
      return s;
    }
    if (inviteStatus(inv) !== "PENDING") {
      pushToast("409 — invite already accepted or revoked", "warn");
      return s;
    }
    return {
      ...s,
      household: {
        ...s.household,
        invites: s.household.invites.map((i) =>
          i.id === inviteId ? { ...i, revokedAt: nowIso(), status: "REVOKED" } : i,
        ),
      },
    };
  });
}

export type AcceptInviteOutcome =
  | "ok"
  | "badRequest"
  | "forbidden"
  | "notFound"
  | "alreadyInHousehold"
  | "gone";

/** Server-side stash so leave→re-accept is demoable: the household survives
 *  on the "server" while the client's /households/current 404s. */
let departedHousehold: StoreState["household"]["current"] = null;

/** POST /invites/accept (§3d status ladder) — 200 returns the new membership,
 *  NOT the household (follow with #1). */
export function acceptInvite(code: string): AcceptInviteOutcome {
  const trimmed = code.trim();
  if (!trimmed || trimmed.length > 32) return "badRequest";
  const fresh = state.session.freshSetup;
  // One household per user (v1): the accepter must not already be in one.
  // In fresh-account demo mode that's judged by the wizard scratch flags.
  if (fresh ? fresh.household : state.household.current != null) {
    return "alreadyInHousehold";
  }
  const entry = Object.entries(state.household.inviteCodes).find(
    ([, c]) => c.toLowerCase() === trimmed.toLowerCase(),
  );
  if (!entry) return "notFound";
  const inv = state.household.invites.find((i) => i.id === entry[0]);
  if (!inv) return "notFound";
  const status = inviteStatus(inv);
  if (status === "EXPIRED" || status === "REVOKED") return "gone";
  if (status === "ACCEPTED") return "gone";
  const me = state.session.user;
  if (inv.issuedForUserId && inv.issuedForUserId !== me?.userId) {
    return "forbidden";
  }
  if (fresh) {
    // Fresh-account wizard branch: joining satisfies household + slots
    // without consuming the real seeded invite.
    markFreshStep("household");
    markFreshStep("lifestyle");
    return "ok";
  }
  const host = departedHousehold;
  if (!host) return "notFound";
  mutate((s) => {
    const member: HouseholdMemberDto = {
      id: `m-${Date.now()}`,
      householdId: host.id,
      userId: me?.userId ?? MOCK_USER_ID,
      role: inv.intendedRole,
      displayName: me?.username ?? null,
      priority: 100,
      joinedAt: nowIso(),
      version: 0,
    };
    const rejoined = { ...host, members: [...host.members, member] };
    return {
      ...s,
      household: {
        ...s.household,
        current: rejoined,
        invites: s.household.invites.map((i) =>
          i.id === inv.id ? { ...i, acceptedAt: nowIso(), status: "ACCEPTED" } : i,
        ),
        resolved: s.household.settings
          ? resolveSlotConfiguration(rejoined, s.household.settings)
          : s.household.resolved,
      },
    };
  });
  departedHousehold = null;
  return "ok";
}

/** PATCH /households/current/members/{id} (#10) — null = no change. */
export function updateMember(memberId: string, req: UpdateMemberRequest): boolean {
  const household = state.household.current;
  const member = household?.members.find((m) => m.id === memberId);
  if (!household || !member) {
    pushToast("404 — member no longer exists", "warn");
    return false;
  }
  if (req.expectedVersion !== member.version) {
    pushToast("409 — member changed elsewhere; re-fetched", "warn");
    return false;
  }
  mutate((s) => {
    const cur = s.household.current;
    if (!cur) return s;
    const next = {
      ...cur,
      members: cur.members.map((m) =>
        m.id === memberId
          ? {
              ...m,
              priority: req.priority ?? m.priority,
              displayName:
                req.displayName !== undefined && req.displayName !== null
                  ? req.displayName
                  : m.displayName,
              version: m.version + 1,
            }
          : m,
      ),
    };
    return { ...s, household: { ...s.household, current: next } };
  });
  return true;
}

/** POST /households/current/members/{id}/role (#12 — POST, not PUT: §8 Q3).
 *  Demoting the last primary → 409. */
export function changeMemberRole(memberId: string, req: ChangeRoleRequest): boolean {
  const household = state.household.current;
  const member = household?.members.find((m) => m.id === memberId);
  if (!household || !member) {
    pushToast("404 — member no longer exists", "warn");
    return false;
  }
  if (req.expectedVersion !== member.version) {
    pushToast("409 — member changed elsewhere; re-fetched", "warn");
    return false;
  }
  const primaries = household.members.filter((m) => m.role === "primary");
  if (
    member.role === "primary" &&
    req.newRole === "member" &&
    primaries.length === 1
  ) {
    pushToast("409 — that's the last primary; promote someone else first", "warn");
    return false;
  }
  mutate((s) => {
    const cur = s.household.current;
    if (!cur) return s;
    return {
      ...s,
      household: {
        ...s.household,
        current: {
          ...cur,
          members: cur.members.map((m) =>
            m.id === memberId
              ? { ...m, role: req.newRole, version: m.version + 1 }
              : m,
          ),
        },
      },
    };
  });
  return true;
}

/** DELETE /households/current/members/{id} (#11) — primary removes anyone;
 *  a member may only self-remove ("Leave household"). Removing the last
 *  primary while others remain → 409. Self-removal clears household scope. */
export function removeMember(memberId: string): "ok" | "left" | "blocked" {
  const household = state.household.current;
  const member = household?.members.find((m) => m.id === memberId);
  if (!household || !member) {
    pushToast("404 — member no longer exists", "warn");
    return "blocked";
  }
  const isSelf = member.userId === state.session.user?.userId;
  const primaries = household.members.filter((m) => m.role === "primary");
  if (
    member.role === "primary" &&
    primaries.length === 1 &&
    household.members.length > 1
  ) {
    pushToast("409 — last primary; promote someone else first", "warn");
    return "blocked";
  }
  if (isSelf) {
    // Stash the household "server-side" so an invite accept can rejoin.
    departedHousehold = {
      ...household,
      members: household.members.filter((m) => m.id !== memberId),
    };
    mutate((s) => ({
      ...s,
      household: { ...s.household, current: null, resolved: null },
    }));
    return "left";
  }
  mutate((s) => {
    const cur = s.household.current;
    if (!cur) return s;
    const next = {
      ...cur,
      members: cur.members.filter((m) => m.id !== memberId),
    };
    return {
      ...s,
      household: {
        ...s.household,
        current: next,
        resolved: s.household.settings
          ? resolveSlotConfiguration(next, s.household.settings)
          : s.household.resolved,
      },
    };
  });
  return "ok";
}

/** Flatten a HouseholdSettingsDocument to fieldPath → value for audit diffs. */
function settingsFieldPaths(
  doc: UpdateHouseholdSettingsRequest["document"],
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, d] of Object.entries(doc.slotDefaults)) {
    out[`slotDefaults.${key}.shared`] = d.shared;
    out[`slotDefaults.${key}.headcount`] = d.headcount ?? null;
    out[`slotDefaults.${key}.timeBudgetMin`] = d.timeBudgetMin ?? null;
  }
  for (const c of doc.customSlots) {
    out[`customSlots[${c.key}]`] = {
      label: c.label,
      backedByKind: c.backedByKind,
      shared: c.shared,
      headcount: c.headcount ?? null,
      timeBudgetMin: c.timeBudgetMin ?? null,
    };
  }
  out["defaultHeadcount"] = doc.defaultHeadcount ?? null;
  return out;
}

/** PUT /households/{id}/settings (#4) — full document replace with
 *  expectedVersion; every changed fieldPath lands an audit row (#5). */
export function saveHouseholdSettings(req: UpdateHouseholdSettingsRequest): boolean {
  const cur = state.household.settings;
  const household = state.household.current;
  if (!cur || !household) return false;
  if (req.expectedVersion !== cur.version) {
    pushToast("409 — settings changed elsewhere; review and save again", "warn");
    return false;
  }
  const before = settingsFieldPaths(cur.document);
  const after = settingsFieldPaths(req.document);
  const audit: HouseholdSettingsAuditEntryDto[] = [];
  for (const path of new Set([...Object.keys(before), ...Object.keys(after)])) {
    if (JSON.stringify(before[path]) !== JSON.stringify(after[path])) {
      audit.push({
        id: `hsa-r${++settingsAuditSeq}`,
        actorUserId: state.session.user?.userId ?? MOCK_USER_ID,
        fieldPath: path,
        previousValue: before[path] ?? null,
        newValue: after[path] ?? null,
        occurredAt: nowIso(),
      });
    }
  }
  mutate((s) => {
    const nextSettings = {
      ...cur,
      document: req.document,
      version: cur.version + 1,
    };
    return {
      ...s,
      household: {
        ...s.household,
        settings: nextSettings,
        settingsAudit: [...audit, ...s.household.settingsAudit],
        // #6 read-back: what the planner will actually see.
        resolved: resolveSlotConfiguration(household, nextSettings),
      },
    };
  });
  pushToast(`Slot configuration saved — ${audit.length} field(s) changed`);
  return true;
}

/* ---- auth / session (login.md, settings.md §3e) -------------------------------------------- */

/** Mock credential store; register() adds to it. The seeded password backs
 *  the change-password 401-generic demo. */
const mockUsers: Record<string, { userId: string; password: string }> = {
  iren: { userId: MOCK_USER_ID, password: "plan-the-week-12" },
};

export const MOCK_SEED_PASSWORD = "plan-the-week-12";

export type LoginOutcome =
  | { kind: "ok" }
  | { kind: "invalid" } // 401 — one generic message, never "no such user"
  | { kind: "locked"; retryAfterS: number } // 423
  | { kind: "throttled"; retryAfterS: number }; // 429

/** POST /auth/login (#1). Demo paths: a username containing "locked" → 423;
 *  5 consecutive failures → 429. Real "now" drives the countdowns. */
export function login(username: string, password: string): LoginOutcome {
  const name = username.trim().toLowerCase();
  const wallNow = Date.now();
  const lockedUntil = state.session.lockedUntilMs;
  if (lockedUntil && lockedUntil > wallNow) {
    const retryAfterS = Math.ceil((lockedUntil - wallNow) / 1000);
    return state.session.lockKind === "locked"
      ? { kind: "locked", retryAfterS }
      : { kind: "throttled", retryAfterS };
  }
  if (name.includes("locked")) {
    mutate((s) => ({
      ...s,
      session: { ...s.session, lockedUntilMs: wallNow + 30_000, lockKind: "locked" },
    }));
    return { kind: "locked", retryAfterS: 30 };
  }
  const known = mockUsers[name];
  if (!known || known.password !== password) {
    const failures = state.session.failedAttempts + 1;
    if (failures >= 5) {
      mutate((s) => ({
        ...s,
        session: {
          ...s.session,
          failedAttempts: 0,
          lockedUntilMs: wallNow + 10_000,
          lockKind: "throttled",
        },
      }));
      return { kind: "throttled", retryAfterS: 10 };
    }
    mutate((s) => ({ ...s, session: { ...s.session, failedAttempts: failures } }));
    return { kind: "invalid" };
  }
  mutate((s) => ({
    ...s,
    session: {
      ...s.session,
      user: { userId: known.userId, username: name },
      failedAttempts: 0,
      lockedUntilMs: null,
      lockKind: null,
    },
  }));
  return { kind: "ok" };
}

export type RegisterOutcome = "ok" | "taken";

/** POST /auth/register (#2) — 201 + cookie = auto-login (locked decision);
 *  a fresh account starts the onboarding probe chain with everything 404. */
export function register(username: string, password: string): RegisterOutcome {
  const name = username.trim().toLowerCase();
  if (mockUsers[name]) return "taken";
  const userId = `user-${name}-${Date.now() % 10_000}`;
  mockUsers[name] = { userId, password };
  mutate((s) => ({
    ...s,
    session: {
      ...s.session,
      user: { userId, username: name },
      failedAttempts: 0,
      lockedUntilMs: null,
      lockKind: null,
      freshSetup: {
        household: false,
        constraints: false,
        lifestyle: false,
        targets: false,
      },
    },
  }));
  return "ok";
}

/** POST /auth/logout — per-device; other sessions survive (GAP-71). The
 *  router guard then redirects every shell route to /login. */
export function logout(): void {
  mutate((s) => ({ ...s, session: { ...s.session, user: null } }));
}

export type ChangePasswordOutcome = "ok" | "unauthorized" | "conflict";

/** PUT /auth/password (#13) — wrong current password is a GENERIC 401 (it
 *  also counts toward the login throttle); success re-issues the calling
 *  session via Set-Cookie and revokes all others (lld/auth.md Flow 5). */
export function changePassword(req: PasswordChangeRequest): ChangePasswordOutcome {
  const me = state.session.user;
  if (!me) return "unauthorized";
  const record = mockUsers[me.username];
  if (!record || record.password !== req.currentPassword) return "unauthorized";
  if (req.newPassword === req.currentPassword) return "conflict";
  record.password = req.newPassword;
  return "ok";
}

/* ---- onboarding (onboarding.md) ------------------------------------------------------------- */

function markFreshStep(step: keyof NonNullable<SessionState["freshSetup"]>): void {
  mutate((s) =>
    s.session.freshSetup
      ? {
          ...s,
          session: {
            ...s.session,
            freshSetup: { ...s.session.freshSetup, [step]: true },
          },
        }
      : s,
  );
}

export { markFreshStep as completeFreshSetupStep };

/** §4 probe chain: first unsatisfied step index (0-based), or null when all
 *  satisfied (→ redirect /). Wizard state is derived, never stored. */
export function onboardingResumeStep(s: StoreState): number | null {
  const fresh = s.session.freshSetup;
  const hasHousehold = fresh ? fresh.household : s.household.current != null;
  if (!hasHousehold) return 0;
  const hasConstraints = fresh
    ? fresh.constraints
    : s.preferences.hardConstraints != null;
  if (!hasConstraints) return 2;
  const hasLifestyle = fresh ? fresh.lifestyle : s.preferences.lifestyle != null;
  if (!hasLifestyle) return 3;
  const hasTargets = fresh ? fresh.targets : s.targets != null;
  if (!hasTargets) return 4;
  return null;
}

/** Demo entry: replay the wizard as a fresh account without nuking the seed. */
export function replayOnboardingAsFresh(): void {
  mutate((s) => ({
    ...s,
    session: {
      ...s.session,
      freshSetup: {
        household: false,
        constraints: false,
        lifestyle: false,
        targets: false,
      },
    },
  }));
}

export function exitOnboarding(): void {
  mutate((s) => ({ ...s, session: { ...s.session, freshSetup: null } }));
}

/* ---- grocery provider connection (settings.md §3f) ------------------------------------------ */

/** PUT /grocery/orders/providers/{key} (#16) — connect / pause / refresh. */
export function saveProviderConnection(req: ProviderConnectionRequest): void {
  mutate((s) => {
    const cur = s.grocery.providerState;
    const next = cur
      ? {
          ...cur,
          providerKey: req.providerKey,
          enabled: req.enabled ?? cur.enabled,
          scheduledRefreshEnabled:
            req.scheduledRefreshEnabled ?? cur.scheduledRefreshEnabled,
          refreshTopNIngredients:
            req.refreshTopNIngredients ?? cur.refreshTopNIngredients,
        }
      : {
          id: `prov-${Date.now()}`,
          userId: s.session.user?.userId ?? MOCK_USER_ID,
          providerKey: req.providerKey,
          enabled: req.enabled ?? true,
          sessionExpiresAt: null,
          lastLoginAt: nowIso(),
          lastFailureAt: null,
          lastFailureReason: null,
          consecutiveFailures: 0,
          scheduledRefreshEnabled: req.scheduledRefreshEnabled ?? false,
          refreshTopNIngredients: req.refreshTopNIngredients ?? 0,
        };
    return { ...s, grocery: { ...s.grocery, providerState: next } };
  });
}

/* ---- admin (admin.md) ------------------------------------------------------------------------ */

/** The shell's once-per-session lazy probe (#1): 200 → reveal the nav entry;
 *  403 → never show it again this session (§5). */
export function probeAdmin(): void {
  if (state.admin.probeOutcome) return;
  mutate((s) => ({
    ...s,
    admin: {
      ...s.admin,
      probeOutcome: s.admin.allowlisted ? "admin" : "denied",
    },
  }));
}

/** Mock-only demo control for the allowlist flag (admin.md §8 delta 1). */
export function setAdminAllowlisted(allowlisted: boolean): void {
  mutate((s) => ({
    ...s,
    admin: {
      ...s.admin,
      allowlisted,
      probeOutcome: allowlisted ? "admin" : "denied",
    },
  }));
}

/** GET /admin/ai/cost-summary (#2) — aggregates the call log over the window
 *  ending at the mock "now"; topUsers capped at 20, spend-descending.
 *  NOT a useStore selector (returns a fresh object) — call it from useMemo. */
export function adminCostSummary(
  callLog: StoreState["admin"]["callLog"],
  windowHours: number,
): CostSummaryDto {
  const cutoff = Date.parse(nowIso()) - windowHours * 3_600_000;
  const rows = callLog.filter((c) => Date.parse(c.createdAt) >= cutoff);
  const byUser = new Map<string, { calls: number; costMicroPence: number }>();
  for (const c of rows) {
    const key = c.userId ?? "system";
    const cur = byUser.get(key) ?? { calls: 0, costMicroPence: 0 };
    byUser.set(key, {
      calls: cur.calls + 1,
      costMicroPence: cur.costMicroPence + c.costMicroPence,
    });
  }
  return {
    windowHours,
    totalCalls: rows.length,
    totalMicroPence: rows.reduce((acc, c) => acc + c.costMicroPence, 0),
    topUsers: [...byUser.entries()]
      .map(([userId, v]) => ({ userId, ...v }))
      .sort((a, b) => b.costMicroPence - a.costMicroPence)
      .slice(0, 20),
  };
}

/** GET /admin/decision-log/{id} (#5). */
export function findDecision(s: StoreState, id: string): DecisionLogDto | undefined {
  return s.admin.decisions.find((d) => d.decisionId === id.trim());
}

/** GET /admin/decision-log/trace/{traceId} (#6) — creation-ordered; an empty
 *  list is a valid result, not an error. */
export function decisionsForTrace(s: StoreState, traceId: string): DecisionLogDto[] {
  return s.admin.decisions
    .filter((d) => d.traceId === traceId.trim())
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

/** GET /admin/decision-log/{id}/ancestry (#7) — walks parentDecisionId up to
 *  maxDepth; hitting the cap sets cycleDetected (§3d red warning). */
export function walkAncestry(
  s: StoreState,
  decisionId: string,
  maxDepth = 32,
): AncestryResponse | null {
  let cur = findDecision(s, decisionId);
  if (!cur) return null;
  const chain: DecisionLogDto[] = [cur];
  let depth = 0;
  while (cur.parentDecisionId && depth < maxDepth) {
    const parent = s.admin.decisions.find(
      (d) => d.decisionId === cur!.parentDecisionId,
    );
    if (!parent) break;
    chain.push(parent);
    cur = parent;
    depth += 1;
  }
  return {
    ancestors: chain.reverse(), // root-first (§3d)
    cycleDetected: depth >= maxDepth && cur.parentDecisionId != null,
  };
}

/** GET /admin/planner/decisions/{planId} (#8) — empty rows for pre-planner-01l
 *  plans (no retroactive backfill). */
export function plannerChainFor(
  s: StoreState,
  planId: string,
  traceId?: string,
): PlannerDecisionChainDto {
  const chain = s.admin.plannerChains[planId.trim()];
  if (!chain) return { planId: planId.trim(), rows: [] };
  if (!traceId?.trim()) return chain;
  return { ...chain, rows: chain.rows.filter((r) => r.traceId === traceId.trim()) };
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
/* The rail badge + bell read selectNotificationSummary (defined with the
 * notifications actions above) — one store backs page, bell and digest. */
