/**
 * Planner seed — production DTO shapes throughout (PlanDto / DayDto /
 * MealSlotDto et al., see design/frontend/pages/plan.md §2/§3).
 *
 * Seeded design-review states:
 * - Week 1–7 June: gen 1 COMPLETED (cold-start plan, all slots settled).
 * - Week 8–14 June (current): gen 1 REJECTED → gen 2 SUPERSEDED → gen 3
 *   ACTIVE mid-week (Mon/Tue eaten, Wed lunch cooked, rest planned).
 * - Week 15–21 June: gen 1 GENERATED awaiting approval, generated against an
 *   infeasible constraint set → qualityWarning + one unfilled slot +
 *   aiAugmented=false ("AI ranking unavailable" badge).
 * - One PENDING re-opt suggestion ("chicken breast marked spoiled") whose
 *   diff is only revealed by the accept response (contract gap, spec §8 Q2).
 * - Feasibility results per week: current week feasible; next week one
 *   NUTRITION_VS_BUDGET conflict + 2 ranked relaxations.
 * - One raced slot (Wed custom slot): first action returns the 409
 *   "advanced on another device" demo from the status-code map.
 */

import { MOCK_TODAY_ISO, WEEK_DATES } from "./nutritionSeed";
import type {
  DailyRollupDocument,
  DayDto,
  FeasibilityCheckResultDto,
  MealSlotDto,
  PinnedReason,
  PlanDto,
  PlannerSlotKind,
  PlannerState,
  ProposedReoptAssignmentsDocument,
  ReoptSuggestionDto,
  RollupSummaryDocument,
  ScoreBreakdownDocument,
  SlotState,
} from "./types";

export const HOUSEHOLD_ID = "hh-veer-0001";

export const PREV_WEEK_START = "2026-06-01";
export const CURRENT_WEEK_START = WEEK_DATES[0]; // 2026-06-08
export const NEXT_WEEK_START = "2026-06-15";

/** Weeks the previous-weeks picker (#4) can navigate. */
export const KNOWN_WEEKS: string[] = [
  PREV_WEEK_START,
  CURRENT_WEEK_START,
  NEXT_WEEK_START,
];

/** Household member ids (eaters[] joins, household seed m1–m4). */
const ALL = ["m1", "m2", "m3", "m4"];
const IREN = ["m1"];

/**
 * Recipe-name cache for ids outside the 12-recipe catalogue — stands in for
 * the client-side GET /recipes/{id} join (plan.md s1): slots carry ids only.
 */
export const RECIPE_NAME_FALLBACK: Record<string, string> = {
  "overnight-oats": "Overnight oats",
  "eggs-on-toast": "Eggs on toast",
  "greek-yoghurt-bowl": "Greek yoghurt bowl",
  "grain-bowl": "Grain bowl",
  "chicken-wrap": "Chicken wrap",
  pancakes: "Pancakes",
  "leftover-curry": "Leftover curry",
  "pizza-night": "Pizza night",
  "soup-bread": "Soup & bread",
  "batch-curry-base": "Batch: curry base",
  "batch-chilli-base": "Batch: chilli base",
  "one-pot-tomato-orzo": "One-pot tomato orzo",
  "veggie-chilli": "Veggie chilli",
  "chicken-pilaf": "Chicken pilaf",
  quesadillas: "Quesadillas",
  "sausage-bake": "Sausage bake",
  "protein-shake": "Protein shake",
  "mushroom-risotto": "Mushroom risotto",
  "prawn-linguine": "Prawn linguine",
};

/* ---- builders ----------------------------------------------------------------- */

export function addDaysIso(iso: string, days: number): string {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

const KIND_BY_INDEX: PlannerSlotKind[] = ["BREAKFAST", "LUNCH", "DINNER"];
const LABEL_BY_KIND: Record<PlannerSlotKind, string> = {
  BREAKFAST: "Breakfast",
  LUNCH: "Lunch",
  DINNER: "Dinner",
  SNACK: "Snack",
  CUSTOM: "Custom",
};

/** Default per-kind wall-clock times (household slot config, s2). */
const TIME_BY_KIND: Record<PlannerSlotKind, string | null> = {
  BREAKFAST: "08:00",
  LUNCH: null, // demos the null mealTime → no time shown gap (spec §8 Q3)
  DINNER: "19:00",
  SNACK: null,
  CUSTOM: null,
};

// Backend (#258) resolves a non-null serve time for every slot. Fixtures fall
// back to a sensible per-kind default when the slot carries no explicit time.
const DEFAULT_EFFECTIVE_TIME_BY_KIND: Record<PlannerSlotKind, string> = {
  BREAKFAST: "08:00",
  LUNCH: "12:30",
  DINNER: "19:00",
  SNACK: "15:00",
  CUSTOM: "12:00",
};

interface SlotSpec {
  recipeId: string | null;
  state?: SlotState;
  /** Defaults to the slot state for terminal/cooking states. */
  pinnedReason?: PinnedReason | null;
  batchCookSessionId?: string | null;
  mealTime?: string | null;
  timeBudgetMin?: number;
  servings?: number;
  shared?: boolean;
  eaters?: string[];
  kind?: PlannerSlotKind;
  label?: string;
  augmentationNotes?: string | null;
  augmentationSource?: "LLM" | "USER" | null;
  phase2Addition?: boolean;
}

const PIN_FOR_STATE: Partial<Record<SlotState, PinnedReason>> = {
  EATEN: "EATEN",
  COOKED: "COOKED",
  COOKING: "COOKING",
  SKIPPED: "SKIPPED",
};

function buildSlot(
  planKey: string,
  date: string,
  slotIndex: number,
  spec: SlotSpec,
): MealSlotDto {
  const kind = spec.kind ?? KIND_BY_INDEX[slotIndex] ?? "CUSTOM";
  const state = spec.state ?? "PLANNED";
  const shared = spec.shared ?? kind === "DINNER";
  const id = `${planKey}-${date}-${kind.toLowerCase()}-${slotIndex}`;
  const mealTime = spec.mealTime !== undefined ? spec.mealTime : TIME_BY_KIND[kind];
  return {
    id,
    slotIndex,
    kind,
    label: spec.label ?? LABEL_BY_KIND[kind],
    timeBudgetMin:
      spec.timeBudgetMin ?? (kind === "DINNER" ? 45 : kind === "BREAKFAST" ? 15 : 20),
    shared,
    eaters: spec.eaters ?? (shared ? ALL : IREN),
    state,
    pinnedReason: spec.pinnedReason ?? PIN_FOR_STATE[state] ?? null,
    mealTime,
    prepStepAtTime: null, // reserved, always null in v1 (spec §7)
    // effectiveMealTime/mealTimeSource (#258): backend always resolves a
    // non-null serve time — an explicit slot time is an override, else a
    // per-kind default.
    effectiveMealTime: mealTime ?? DEFAULT_EFFECTIVE_TIME_BY_KIND[kind],
    mealTimeSource: spec.mealTime != null ? "SLOT_OVERRIDE" : "KIND_DEFAULT",
    scheduledRecipe:
      spec.recipeId === null
        ? null
        : {
            id: `sr-${id}`,
            recipeId: spec.recipeId,
            recipeVersionId: `${spec.recipeId}-v1`,
            recipeBranchId: `${spec.recipeId}-main`,
            servings: spec.servings ?? (shared ? 4 : 1),
            batchCookSessionId: spec.batchCookSessionId ?? null,
            augmentationNotes: spec.augmentationNotes ?? null,
            augmentationSource: spec.augmentationSource ?? null,
            phase2Addition: spec.phase2Addition ?? false,
          },
  };
}

/** A standard B/L/D day; extra slots (SNACK/CUSTOM) appended after index 2. */
function buildDay(
  planKey: string,
  weekStart: string,
  dayOffset: number,
  slots: SlotSpec[],
  notes?: string | null,
): DayDto {
  const date = addDaysIso(weekStart, dayOffset);
  return {
    id: `${planKey}-${date}`,
    date,
    notes: notes ?? null,
    slots: slots.map((spec, i) => buildSlot(planKey, date, i, spec)),
  };
}

function score(
  composite: number,
  parts: Partial<ScoreBreakdownDocument>,
): ScoreBreakdownDocument {
  return {
    preference: 0.9,
    nutrition: 0.88,
    cost: 0.86,
    variety: 0.78,
    time: 0.92,
    batch: 0.84,
    provisions: 0.9,
    composite,
    nutritionFloorGatePassed: true,
    varietyGatePassed: true,
    weightSchemeVersion: "v3",
    ...parts,
  };
}

function dailyRollup(
  date: string,
  costGbp: number,
  totalTimeMin: number,
  violations: string[] = [],
): DailyRollupDocument {
  return {
    date,
    kcal: 2150,
    proteinG: 168,
    fatG: 68,
    carbsG: 220,
    fibreG: 26,
    costGbp,
    totalTimeMin,
    violations,
  };
}

function rollups(
  weekStart: string,
  weekly: Partial<RollupSummaryDocument["weekly"]>,
  perDay?: Array<Partial<{ costGbp: number; totalTimeMin: number; violations: string[] }>>,
): RollupSummaryDocument {
  return {
    daily: Array.from({ length: 7 }, (_, i) =>
      dailyRollup(
        addDaysIso(weekStart, i),
        perDay?.[i]?.costGbp ?? 7.4,
        perDay?.[i]?.totalTimeMin ?? 32,
        perDay?.[i]?.violations ?? [],
      ),
    ),
    weekly: {
      kcalTotal: 15050,
      proteinAvgG: 168,
      fatAvgG: 68,
      carbsAvgG: 220,
      costEstimateGbp: 52,
      costConfidence: 0.83,
      staleIngredientCount: 4,
      varietyIndex: 0.78,
      batchCookSessions: 2,
      constraintViolations: [],
      ...weekly,
    },
  };
}

interface PlanSpec {
  id: string;
  generation: number;
  replacesPlanId?: string | null;
  weekStartDate: string;
  status: PlanDto["status"];
  triggerKind: PlanDto["triggerKind"];
  qualityWarning?: boolean;
  coldStart?: boolean;
  aiAugmented?: boolean;
  createdAt: string;
  acceptedAt?: string | null;
  completedAt?: string | null;
  rejectedAt?: string | null;
  rejectedReason?: string | null;
  abandonedAt?: string | null;
  abandonedReason?: string | null;
  scoreBreakdown: ScoreBreakdownDocument;
  rollupSummary: RollupSummaryDocument;
  days: DayDto[];
  version?: number;
}

export function buildPlan(spec: PlanSpec): PlanDto {
  return {
    id: spec.id,
    householdId: HOUSEHOLD_ID,
    weekStartDate: spec.weekStartDate,
    generation: spec.generation,
    replacesPlanId: spec.replacesPlanId ?? null,
    status: spec.status,
    triggerKind: spec.triggerKind,
    triggerEventId: null,
    qualityWarning: spec.qualityWarning ?? false,
    coldStart: spec.coldStart ?? false,
    aiAugmented: spec.aiAugmented ?? true,
    traceId: `trace-${spec.id}`,
    decisionId: `decision-${spec.id}`,
    acceptedAt: spec.acceptedAt ?? null,
    completedAt: spec.completedAt ?? null,
    rejectedAt: spec.rejectedAt ?? null,
    rejectedReason: spec.rejectedReason ?? null,
    abandonedAt: spec.abandonedAt ?? null,
    abandonedReason: spec.abandonedReason ?? null,
    scoreBreakdown: spec.scoreBreakdown,
    rollupSummary: spec.rollupSummary,
    days: spec.days,
    version: spec.version ?? 1,
    createdAt: spec.createdAt,
    updatedAt: spec.createdAt,
  };
}

/* ---- previous week: gen 1 COMPLETED (cold-start) -------------------------------- */

const prevLineup: Array<[string | null, string | null, string | null]> = [
  ["overnight-oats", "soup-bread", "salmon-traybake"],
  ["eggs-on-toast", "grain-bowl", "pasta-norma"],
  ["overnight-oats", "leftover-curry", "fish-tacos"],
  ["greek-yoghurt-bowl", "chicken-wrap", "gnocchi-al-forno"],
  ["eggs-on-toast", "grain-bowl", "shakshuka"],
  ["pancakes", "soup-bread", "pizza-night"],
  ["shakshuka", "leftover-curry", "batch-chilli-base"],
];

const planPrev = buildPlan({
  id: "plan-w23-g1",
  generation: 1,
  weekStartDate: PREV_WEEK_START,
  status: "COMPLETED",
  triggerKind: "SCHEDULED_WEEKLY",
  coldStart: true, // first household plan — "early-days" badge in history
  createdAt: "2026-05-31T06:00:00Z",
  acceptedAt: "2026-05-31T09:12:00Z",
  completedAt: "2026-06-08T00:00:00Z",
  scoreBreakdown: score(0.74, { preference: 0.62, variety: 0.7 }),
  rollupSummary: rollups(PREV_WEEK_START, {
    costEstimateGbp: 49.4,
    costConfidence: 0.71,
    staleIngredientCount: 0,
    varietyIndex: 0.7,
    batchCookSessions: 1,
  }),
  days: prevLineup.map(([b, l, d], i) =>
    buildDay("p1", PREV_WEEK_START, i, [
      { recipeId: b, state: "EATEN" },
      // One mid-week skip demos the terminal "— skipped" mark in history.
      { recipeId: l, state: i === 2 ? "SKIPPED" : "EATEN" },
      {
        recipeId: d,
        state: "EATEN",
        batchCookSessionId: i === 6 ? "bcs-w23-1" : null,
      },
    ]),
  ),
});

/* ---- current week: gen 1 REJECTED → gen 2 SUPERSEDED → gen 3 ACTIVE -------------- */

const g1Lineup: Array<[string, string, string]> = [
  ["overnight-oats", "soup-bread", "salmon-traybake"],
  ["eggs-on-toast", "grain-bowl", "veggie-chilli"],
  ["overnight-oats", "leftover-curry", "chicken-pilaf"],
  ["greek-yoghurt-bowl", "chicken-wrap", "pasta-norma"],
  ["eggs-on-toast", "grain-bowl", "fish-tacos"],
  ["pancakes", "soup-bread", "pizza-night"],
  ["shakshuka", "leftover-curry", "batch-curry-base"],
];

const planG1 = buildPlan({
  id: "plan-w24-g1",
  generation: 1,
  weekStartDate: CURRENT_WEEK_START,
  status: "REJECTED",
  triggerKind: "SCHEDULED_WEEKLY",
  createdAt: "2026-06-07T06:00:00Z",
  rejectedAt: "2026-06-07T08:40:00Z",
  rejectedReason: "Too many repeats from last week",
  scoreBreakdown: score(0.81, { variety: 0.64 }),
  rollupSummary: rollups(CURRENT_WEEK_START, {
    costEstimateGbp: 47.2,
    varietyIndex: 0.64,
    batchCookSessions: 1,
  }),
  days: g1Lineup.map(([b, l, d], i) =>
    buildDay("p1w24", CURRENT_WEEK_START, i, [
      { recipeId: b },
      { recipeId: l },
      { recipeId: d, batchCookSessionId: i === 6 ? "bcs-w24-0" : null },
    ]),
  ),
});

const g2Lineup: Array<[string, string, string]> = [
  ["overnight-oats", "chicken-stir-fry", "salmon-traybake"],
  ["eggs-on-toast", "chicken-stir-fry", "pasta-norma"],
  ["overnight-oats", "chicken-stir-fry", "tofu-bibimbap"],
  ["greek-yoghurt-bowl", "grain-bowl", "chicken-stir-fry"],
  ["eggs-on-toast", "chicken-wrap", "fish-tacos"],
  ["pancakes", "leftover-curry", "pizza-night"],
  ["shakshuka", "soup-bread", "batch-curry-base"],
];

const planG2 = buildPlan({
  id: "plan-w24-g2",
  generation: 2,
  replacesPlanId: planG1.id,
  weekStartDate: CURRENT_WEEK_START,
  status: "SUPERSEDED",
  triggerKind: "USER_INITIATED",
  createdAt: "2026-06-07T09:05:00Z",
  acceptedAt: "2026-06-07T09:20:00Z",
  scoreBreakdown: score(0.89, {}),
  rollupSummary: rollups(CURRENT_WEEK_START, {
    costEstimateGbp: 51.1,
    varietyIndex: 0.75,
  }),
  days: g2Lineup.map(([b, l, d], i) =>
    buildDay("p2w24", CURRENT_WEEK_START, i, [
      { recipeId: b },
      { recipeId: l, batchCookSessionId: i <= 2 ? "bcs-w24-1" : null },
      { recipeId: d, batchCookSessionId: i === 6 ? "bcs-w24-2" : null },
    ]),
  ),
});

/** The ACTIVE plan, mid-week (Mon/Tue eaten · Wed lunch cooked · rest ahead). */
const planG3 = buildPlan({
  id: "plan-w24-g3",
  generation: 3,
  replacesPlanId: planG2.id,
  weekStartDate: CURRENT_WEEK_START,
  status: "ACTIVE",
  triggerKind: "USER_INITIATED",
  createdAt: "2026-06-09T17:55:00Z",
  acceptedAt: "2026-06-09T18:10:00Z",
  qualityWarning: true,
  scoreBreakdown: score(0.91, {
    preference: 0.93,
    nutrition: 0.88,
    cost: 0.86,
    variety: 0.78,
    time: 0.92,
    batch: 0.84,
    provisions: 0.9,
    nutritionFloorGatePassed: false, // Thursday protein floor missed
  }),
  rollupSummary: rollups(
    CURRENT_WEEK_START,
    {
      constraintViolations: [
        "Protein 12 g below the 120 g daily floor on Thursday",
        "Fibre below the 25 g floor on 3 days",
      ],
    },
    [
      {},
      {},
      {},
      { violations: ["Protein 108 g vs 120 g floor"] },
      { violations: ["Fibre 21 g vs 25 g floor"] },
      { violations: ["Fibre 22 g vs 25 g floor"] },
      { violations: ["Fibre 23 g vs 25 g floor"], totalTimeMin: 95 },
    ],
  ),
  days: [
    buildDay("p3", CURRENT_WEEK_START, 0, [
      { recipeId: "overnight-oats", state: "EATEN" },
      { recipeId: "chicken-stir-fry", state: "EATEN", batchCookSessionId: "bcs-w24-1" },
      { recipeId: "salmon-traybake", state: "EATEN" },
    ]),
    buildDay("p3", CURRENT_WEEK_START, 1, [
      { recipeId: "eggs-on-toast", state: "EATEN" },
      { recipeId: "chicken-stir-fry", state: "EATEN", batchCookSessionId: "bcs-w24-1" },
      { recipeId: "pasta-norma", state: "EATEN" },
    ]),
    buildDay("p3", CURRENT_WEEK_START, 2, [
      { recipeId: "overnight-oats", state: "EATEN" },
      { recipeId: "chicken-stir-fry", state: "COOKED", batchCookSessionId: "bcs-w24-1" },
      { recipeId: "tofu-bibimbap", state: "PLANNED", timeBudgetMin: 25 },
      {
        recipeId: "protein-shake",
        kind: "CUSTOM",
        label: "Post-gym shake",
        mealTime: "17:30",
        timeBudgetMin: 5,
        shared: false,
        augmentationNotes: "Added for your evening gym class",
        augmentationSource: "LLM",
        phase2Addition: true,
      },
    ]),
    buildDay("p3", CURRENT_WEEK_START, 3, [
      { recipeId: "greek-yoghurt-bowl" },
      { recipeId: "grain-bowl" },
      { recipeId: "chicken-stir-fry" }, // affected by the pending suggestion
    ]),
    buildDay("p3", CURRENT_WEEK_START, 4, [
      { recipeId: "eggs-on-toast" },
      { recipeId: "chicken-wrap" }, // affected by the pending suggestion
      { recipeId: "fish-tacos" },
    ]),
    buildDay(
      "p3",
      CURRENT_WEEK_START,
      5,
      [
        { recipeId: "pancakes", mealTime: "09:00" },
        { recipeId: "leftover-curry" },
        { recipeId: "pizza-night", state: "PLANNED", pinnedReason: "USER_PINNED", mealTime: "18:00" },
      ],
      "Maya's match day — early dinner",
    ),
    buildDay("p3", CURRENT_WEEK_START, 6, [
      { recipeId: "shakshuka" },
      { recipeId: "soup-bread" },
      { recipeId: "batch-curry-base", batchCookSessionId: "bcs-w24-2", timeBudgetMin: 90 },
    ]),
  ],
  version: 7, // slot transitions force-bump the plan version
});

/* ---- next week: gen 1 GENERATED awaiting approval -------------------------------- */

const nextLineup: Array<[string, string, string | null]> = [
  ["overnight-oats", "grain-bowl", "miso-salmon-traybake"],
  ["eggs-on-toast", "soup-bread", "black-bean-tacos"],
  ["greek-yoghurt-bowl", "leftover-curry", "chicken-pilaf"],
  ["overnight-oats", "grain-bowl", null], // unfilled — no feasible recipe
  ["eggs-on-toast", "chicken-wrap", "prawn-stir-fry"],
  ["pancakes", "soup-bread", "gnocchi-al-forno"],
  ["shakshuka", "leftover-curry", "batch-chilli-base"],
];

const planNext = buildPlan({
  id: "plan-w25-g1",
  generation: 1,
  weekStartDate: NEXT_WEEK_START,
  status: "GENERATED",
  triggerKind: "USER_INITIATED",
  createdAt: "2026-06-10T08:05:00Z",
  qualityWarning: true, // generated against an infeasible set (no silent relax)
  aiAugmented: false, // Stage C fallback → "AI ranking unavailable" badge
  scoreBreakdown: score(0.79, {
    nutrition: 0.71,
    cost: 0.8,
    nutritionFloorGatePassed: false,
  }),
  rollupSummary: rollups(
    NEXT_WEEK_START,
    {
      costEstimateGbp: 54.6,
      costConfidence: 0.76,
      staleIngredientCount: 2,
      varietyIndex: 0.81,
      constraintViolations: [
        "Protein floor 120 g unmet on Tue and Thu within the £55 budget",
        "Thu dinner unfilled — no feasible recipe under current constraints",
      ],
    },
    [
      {},
      { violations: ["Protein 112 g vs 120 g floor"] },
      {},
      { violations: ["Protein 104 g vs 120 g floor", "Dinner slot unfilled"] },
      {},
      {},
      {},
    ],
  ),
  days: nextLineup.map(([b, l, d], i) =>
    buildDay("pn", NEXT_WEEK_START, i, [
      { recipeId: b },
      { recipeId: l },
      { recipeId: d, batchCookSessionId: i === 6 ? "bcs-w25-1" : null },
    ]),
  ),
});

/* ---- re-opt suggestion (PENDING) -------------------------------------------------- */

const AFFECTED_THU_DINNER = "p3-2026-06-11-dinner-2";
const AFFECTED_FRI_LUNCH = "p3-2026-06-12-lunch-1";

const suggestionSeed: ReoptSuggestionDto = {
  id: "sg-1",
  householdId: HOUSEHOLD_ID,
  weekStartDate: CURRENT_WEEK_START,
  planId: planG3.id,
  triggerKind: "PROVISIONS",
  triggerEventId: "evt-spoil-chicken",
  affectedSlotIds: [AFFECTED_THU_DINNER, AFFECTED_FRI_LUNCH],
  summary: "Chicken breast marked spoiled",
  status: "PENDING",
  expiresAt: "2026-06-15T00:00:00Z", // weekStart + 7d → "expires Sunday"
  createdAt: "2026-06-10T07:02:00Z",
  resolvedAt: null,
};

/** The diff only the accept/reject *response* carries (spec §8 Q2). */
const suggestionProposal: ProposedReoptAssignmentsDocument = {
  schemaVersion: 1,
  changes: [
    {
      slotId: AFFECTED_THU_DINNER,
      oldRecipeId: "chicken-stir-fry",
      newRecipeId: "chickpea-spinach-curry",
      newRecipeVersionId: "chickpea-spinach-curry-v1",
      newRecipeBranchId: "chickpea-spinach-curry-main",
      newServings: 4,
      reason: "uses expiring spinach",
    },
    {
      slotId: AFFECTED_FRI_LUNCH,
      oldRecipeId: "chicken-wrap",
      newRecipeId: "tuna-melt",
      newRecipeVersionId: "tuna-melt-v1",
      newRecipeBranchId: "tuna-melt-main",
      newServings: 1,
      reason: "pantry-friendly",
    },
  ],
};

/* ---- feasibility (#5), keyed by weekStartDate ------------------------------------- */

const feasibilitySeed: Record<string, FeasibilityCheckResultDto> = {
  [CURRENT_WEEK_START]: { feasible: true, conflicts: [], resolutions: [] },
  [NEXT_WEEK_START]: {
    feasible: false,
    conflicts: [
      {
        type: "NUTRITION_VS_BUDGET",
        affectedSlotIds: [
          "pn-2026-06-16-dinner-2",
          "pn-2026-06-18-dinner-2",
          "pn-2026-06-20-dinner-2",
        ],
        description:
          "The 120 g daily protein floor is not reachable within the £55 weekly budget at current prices.",
      },
    ],
    resolutions: [
      {
        key: "drop_protein_floor_to_110",
        description: "Drop the protein floor to 110 g",
        slotsRecovered: 12,
        scoreRecovered: 0.18,
      },
      {
        key: "raise_budget_to_62",
        description: "Raise the weekly budget to £62",
        slotsRecovered: 9,
        scoreRecovered: 0.12,
      },
    ],
  },
};

/* ---- root -------------------------------------------------------------------------- */

export function createPlannerSeed(): PlannerState {
  return {
    plans: [planNext, planG3, planG2, planG1, planPrev],
    suggestions: [suggestionSeed],
    proposedBySuggestion: { [suggestionSeed.id]: suggestionProposal },
    lastReoptOutcome: null,
    feasibility: feasibilitySeed,
    // Wed custom slot: the mock "server" already saw it eaten on another
    // device — first action 409s and the grid re-fetches (spec §8).
    racedSlot: {
      slotId: `p3-${MOCK_TODAY_ISO}-custom-3`,
      serverState: "EATEN",
    },
    generation: {
      status: "idle",
      weekStartDate: NEXT_WEEK_START,
      forceRegenerateIfActive: false,
      idempotencyKey: null,
      served: {},
      resultPlanId: null,
      replayed: false,
      round: 0,
    },
  };
}
