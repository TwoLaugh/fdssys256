/**
 * Preference + feedback seeds on the production contract DTOs
 * (design/frontend/pages/preferences.md §2, activity.md §2). Mock "today"
 * is Wednesday 10 June 2026 (MOCK_TODAY_ISO) — expiry countdowns and
 * relative dates key off it.
 */

import { MOCK_USER_ID } from "./nutritionSeed";
import type {
  ActivityState,
  ClarificationQueryDto,
  FeedbackEntryDto,
  HardConstraintsAuditEntryDto,
  HardConstraintsDto,
  LifestyleConfigAuditEntryDto,
  LifestyleConfigDto,
  MisclassificationCorrectionDto,
  PreferenceArchiveEntryDto,
  PreferenceLifestyleConfigDocument,
  PreferencesState,
  TasteProfileAuditEntryDto,
  TasteProfileDocument,
  TasteProfileDto,
  TasteProfileVersionDto,
} from "./types";

const at = (date: string, time: string): string => `${date}T${time}:00Z`;

/* ==== taste profile (preferences.md §3) ============================================ */

export const TASTE_PROFILE_ID = "taste-profile-0001";

/** v14 — the current document. Every §3b card has a populated source. */
const documentV14: TasteProfileDocument = {
  lastUpdated: "2026-06-09",
  version: 14,
  basedOnFeedbackCount: 142,
  feedbackCursor: "fb-302",
  softConstraints: {
    intolerances: [
      {
        substance: "lactose",
        severity: "mild",
        notes: "Fine in small amounts; avoid cream-heavy sauces.",
      },
    ],
  },
  flavourPreferences: {
    likes: ["gochujang heat", "citrus", "fresh herbs", "smoky paprika"],
    dislikes: ["very salty", "overly sweet mains"],
    notes:
      "Bright acid plus heat is the through-line — lime over lemon when given the choice.",
  },
  texturePreferences: {
    likes: ["crispy edges", "charred"],
    dislikes: ["slimy", "uniformly soft"],
  },
  ingredientPreferences: {
    favourites: [
      { item: "tofu", evidenceCount: 23, lastSignal: "2026-06-08", source: "FEEDBACK" },
      { item: "salmon", evidenceCount: 17, lastSignal: "2026-06-06", source: "FEEDBACK" },
      { item: "chickpeas", evidenceCount: 12, lastSignal: "2026-06-02", source: "FEEDBACK" },
      { item: "aubergine", evidenceCount: 9, lastSignal: "2026-05-28", source: "INFERRED" },
      { item: "lime", evidenceCount: 2, lastSignal: "2026-04-12", source: "ONBOARDING" },
    ],
    disliked: [
      { item: "celery", evidenceCount: 8, lastSignal: "2026-05-30", source: "FEEDBACK" },
      { item: "blue cheese", evidenceCount: 3, lastSignal: "2026-04-12", source: "ONBOARDING" },
    ],
    trendingPositive: [
      { item: "kimchi", evidenceCount: 2, firstSignal: "2026-05-24" },
      { item: "miso", evidenceCount: 2, firstSignal: "2026-06-01" },
    ],
    trendingNegative: [{ item: "double cream", evidenceCount: 2, firstSignal: "2026-05-18" }],
  },
  cuisinePreferences: {
    favourites: ["korean", "italian"],
    enjoys: ["mexican", "middle eastern"],
    lessPreferred: ["creamy french"],
    notes: "Korean–Italian crossover dishes have landed well.",
  },
  cookingPreferences: {
    skillLevel: "INTERMEDIATE",
    preferredMethods: ["traybake", "stir-fry", "one-pot"],
    dislikedMethods: ["deep-frying"],
  },
  portionStyle: {
    preference: "Generous mains, light sides.",
    saladMeals: "As a side, rarely as the main.",
  },
  householdContext: {
    individualOnlyPreferences: ["extra chilli oil", "coriander garnish"],
    householdSuitableNotes: "Kids' plates are split off before chilli is added.",
  },
  recipesToRepeat: [
    {
      name: "Shakshuka",
      suitableFor: "weekend breakfast",
      reason: "asked for it weekly",
    },
    {
      name: "Harissa chickpea traybake",
      suitableFor: "weeknight dinner",
      reason: "two clean plates, zero leftovers",
    },
  ],
  recipesToAvoid: [
    { name: "Celery soup", suitableFor: null, reason: "celery dominant" },
  ],
  activeExperiments: [
    {
      hypothesis: "Prefers fish twice a week when one is a traybake",
      status: "TESTING",
      evidenceFor: 3,
      evidenceAgainst: 1,
      created: "2026-05-20",
    },
    {
      hypothesis: "Batch-cooked grains get eaten when pre-portioned",
      status: "PROMOTED",
      evidenceFor: 5,
      evidenceAgainst: 0,
      created: "2026-04-28",
    },
  ],
  learnedInsights: [
    "Weeknight dinners over 30 minutes get skipped or swapped.",
    "Leftovers travel to work only when packable was flagged.",
    "Spice tolerance is higher at dinner than at lunch.",
  ],
};

/** v13 — before the latest BATCH delta (no kimchi trend, two insights). */
const documentV13: TasteProfileDocument = {
  ...documentV14,
  lastUpdated: "2026-06-01",
  version: 13,
  basedOnFeedbackCount: 131,
  feedbackCursor: "fb-281",
  ingredientPreferences: {
    ...documentV14.ingredientPreferences,
    favourites: documentV14.ingredientPreferences?.favourites?.map((f) =>
      f.item === "tofu" ? { ...f, evidenceCount: 21, lastSignal: "2026-05-30" } : f,
    ),
    trendingPositive: [{ item: "miso", evidenceCount: 2, firstSignal: "2026-06-01" }],
  },
  learnedInsights: (documentV14.learnedInsights ?? []).slice(0, 2),
};

/** v12 — the manual override snapshot (mushrooms removed from dislikes). */
const documentV12: TasteProfileDocument = {
  ...documentV13,
  lastUpdated: "2026-05-26",
  version: 12,
  basedOnFeedbackCount: 124,
  feedbackCursor: "fb-262",
  ingredientPreferences: {
    ...documentV13.ingredientPreferences,
    trendingPositive: [],
  },
  activeExperiments: (documentV14.activeExperiments ?? []).slice(1),
};

const tasteProfileSeed: TasteProfileDto = {
  id: TASTE_PROFILE_ID,
  userId: MOCK_USER_ID,
  document: documentV14,
  documentVersion: 14,
  feedbackCursor: "fb-302",
  basedOnFeedbackCount: 142,
  lastDeltaAppliedAt: at("2026-06-09", "21:00"),
  lastTokenEstimate: 1840,
  tasteVectorStatus: "EMBEDDED",
  optimisticVersion: 27,
  createdAt: at("2026-04-12", "10:05"),
  updatedAt: at("2026-06-09", "21:00"),
};

const versionRow = (args: {
  n: number;
  snapshot: TasteProfileDocument;
  trigger: TasteProfileVersionDto["trigger"];
  generatedAt: string;
  rangeStart?: string | null;
  rangeEnd?: string | null;
  deltas: unknown;
  modelTier?: string;
}): TasteProfileVersionDto => ({
  id: `tpv-${args.n}`,
  tasteProfileId: TASTE_PROFILE_ID,
  documentVersion: args.n,
  documentSnapshot: args.snapshot,
  feedbackRangeStart: args.rangeStart ?? null,
  feedbackRangeEnd: args.rangeEnd ?? null,
  trigger: args.trigger,
  deltasApplied: args.deltas,
  modelTierUsed: args.modelTier ?? "MID",
  generatedAt: args.generatedAt,
});

/** Retention window: the server keeps a bounded set — 3 rows here (newest first). */
const tasteVersionsSeed: TasteProfileVersionDto[] = [
  versionRow({
    n: 14,
    snapshot: documentV14,
    trigger: "BATCH",
    generatedAt: at("2026-06-09", "21:00"),
    rangeStart: "fb-282",
    rangeEnd: "fb-302",
    deltas: [
      { op: "trend_up", path: "ingredientPreferences.trendingPositive", item: "kimchi" },
      { op: "add", path: "learnedInsights", value: "Spice tolerance is higher at dinner than at lunch." },
    ],
  }),
  versionRow({
    n: 13,
    snapshot: documentV13,
    trigger: "WEEKLY",
    generatedAt: at("2026-06-01", "06:00"),
    rangeStart: "fb-263",
    rangeEnd: "fb-281",
    deltas: [{ op: "trend_up", path: "ingredientPreferences.trendingPositive", item: "miso" }],
  }),
  versionRow({
    n: 12,
    snapshot: documentV12,
    trigger: "MANUAL",
    generatedAt: at("2026-05-26", "18:40"),
    deltas: [{ op: "manual_override", summary: "mushrooms removed from dislikes" }],
    modelTier: "NONE",
  }),
];

const tasteAuditRow = (args: {
  n: number;
  changeType: TasteProfileAuditEntryDto["changeType"];
  actorType: TasteProfileAuditEntryDto["actorType"];
  prev: number | null;
  next: number;
  summary: string | null;
  occurredAt: string;
}): TasteProfileAuditEntryDto => ({
  id: `tpa-${args.n}`,
  actorUserId: MOCK_USER_ID,
  actorType: args.actorType,
  changeType: args.changeType,
  previousDocumentVersion: args.prev,
  newDocumentVersion: args.next,
  summary: args.summary,
  traceId: null,
  occurredAt: args.occurredAt,
});

const tasteAuditSeed: TasteProfileAuditEntryDto[] = [
  tasteAuditRow({
    n: 6, changeType: "AI_DELTA_APPLIED", actorType: "AI", prev: 13, next: 14,
    summary: "2 deltas applied — kimchi trending up; weeknight-time insight added",
    occurredAt: at("2026-06-09", "21:00"),
  }),
  tasteAuditRow({
    n: 5, changeType: "REFRESH_TRIGGERED", actorType: "USER", prev: 13, next: 13,
    summary: "Manual refresh requested", occurredAt: at("2026-06-09", "20:58"),
  }),
  tasteAuditRow({
    n: 4, changeType: "AI_DELTA_APPLIED", actorType: "AI", prev: 12, next: 13,
    summary: "Weekly batch — miso trending up", occurredAt: at("2026-06-01", "06:00"),
  }),
  tasteAuditRow({
    n: 3, changeType: "MANUAL_OVERRIDE", actorType: "USER", prev: 11, next: 12,
    summary: "You removed “mushrooms” from dislikes", occurredAt: at("2026-05-26", "18:40"),
  }),
  tasteAuditRow({
    n: 2, changeType: "ROLLED_BACK", actorType: "USER", prev: 10, next: 11,
    summary: "Restored v9 as v11; later feedback replayed", occurredAt: at("2026-05-12", "08:15"),
  }),
  tasteAuditRow({
    n: 1, changeType: "INITIALIZED", actorType: "SYSTEM", prev: null, next: 1,
    summary: "Seeded from your onboarding quiz", occurredAt: at("2026-04-12", "10:05"),
  }),
];

/* ==== hard constraints (preferences.md §4) ========================================= */

const hardConstraintsSeed: HardConstraintsDto = {
  id: "hard-constraints-0001",
  userId: MOCK_USER_ID,
  allergies: ["peanuts", "tree nuts"],
  medicalDiets: ["low_sodium"],
  dietaryIdentity: {
    base: "vegetarian",
    labelForDisplay: "flexible vegetarian",
    exceptions: [
      { allows: "fish", frequency: "1-2x/week", context: "social" },
      // "X-free" widening exception — allowed even against a dairy concern;
      // untagged dairy items still flag AMBIGUOUS (lld/preference.md Flow 2 §8).
      { allows: "lactose_free", frequency: null, context: "any" },
    ],
  },
  intolerances: [
    { substance: "gluten", severity: "coeliac", notes: "Cross-contamination matters." },
  ],
  ageRestrictions: [
    { ruleKey: "no_whole_nuts_under_5", autoPopulated: true },
    { ruleKey: "no_honey_under_1", autoPopulated: true },
  ],
  version: 7,
};

const hardAuditSeed: HardConstraintsAuditEntryDto[] = [
  {
    id: "hca-3",
    hardConstraintsId: "hard-constraints-0001",
    actorUserId: MOCK_USER_ID,
    fieldChanged: "medicalDiets",
    previousValueJson: [],
    newValueJson: ["low_sodium"],
    occurredAt: at("2026-05-30", "07:40"),
  },
  {
    id: "hca-2",
    hardConstraintsId: "hard-constraints-0001",
    actorUserId: MOCK_USER_ID,
    fieldChanged: "dietaryIdentity",
    previousValueJson: { base: "omnivore", exceptions: [] },
    newValueJson: { base: "vegetarian", exceptions: [{ allows: "fish", context: "social" }] },
    occurredAt: at("2026-04-20", "19:02"),
  },
  {
    id: "hca-1",
    hardConstraintsId: "hard-constraints-0001",
    actorUserId: MOCK_USER_ID,
    fieldChanged: "allergies",
    previousValueJson: ["peanuts"],
    newValueJson: ["peanuts", "tree nuts"],
    occurredAt: at("2026-04-13", "08:11"),
  },
];

/* ==== lifestyle config (preferences.md §5) ========================================= */

const lifestyleDocumentSeed: PreferenceLifestyleConfigDocument = {
  mealStructure: {
    weekday: {
      meals: ["breakfast", "lunch", "dinner"],
      snacks: { planned: true, style: "fruit + nuts", notes: "one planned snack on work days" },
    },
    weekend: {
      meals: ["brunch", "dinner"],
      snacks: { planned: false, style: null, notes: null },
    },
    recurringSkips: [{ day: "friday", meal: "dinner", reason: "takeaway night" }],
  },
  mealTiming: {
    preferredSchedule: {
      times: { breakfast: "08:00-08:30", lunch: "13:00-13:30", dinner: "19:00-19:45" },
    },
    flexibility: "±30 min on weekdays, looser at weekends",
    notes: null,
  },
  noveltyTolerance: {
    bySlot: {
      breakfast: { mode: "rotation", rotationSize: 5 },
      lunch: { mode: "batch_repeat", maxConsecutiveSame: 2 },
      dinner: { mode: "high_variety", weeklyUniqueMinimum: 4, newPerWeek: 1 },
    },
    recipeRepeatCooldownWeeks: { dinner: 3, lunch: 1 },
    ingredientFrequencyCaps: { chicken: "3x/week", salmon: "2x/week" },
  },
  cookingContexts: {
    byContext: {
      weeknight: {
        maxTimeMins: 30,
        complexity: "simple",
        preferredStyles: ["traybake", "one-pot"],
        preferredIngredientCount: { min: 5, max: 10 },
        notes: "school-run evenings",
        source: "onboarding",
        frequency: "4x/week",
      },
      weekend: {
        maxTimeMins: 90,
        complexity: "involved ok",
        preferredStyles: ["project cooking"],
        preferredIngredientCount: { min: 6, max: 16 },
        notes: null,
        source: null,
        frequency: null,
      },
    },
  },
  batchCooking: {
    prepDays: [{ day: "sunday", window: "15:00-18:00", maxSessionHours: 3, maxRecipes: 3 }],
    maxLeftoverDays: { curry: 3, rice: 2 },
    leftoverStrategy: "lunches first",
    freezerTolerance: {
      acceptable: true,
      maxFrozenMealsPerWeek: 2,
      exclusions: ["salads", "fried things"],
    },
    sameProteinSameDay: false,
    parallelCookingTolerance: "two pans max",
  },
  reheatingPreferences: {
    availableAtWork: ["microwave"],
    availableAtHome: ["oven", "microwave", "air fryer"],
    preferredMethod: "oven where it matters",
    exclusions: [{ category: "fried", rule: "never microwave", reason: "goes soggy" }],
    coldMealTolerance: ["grain bowls", "frittata"],
  },
  eatingContext: {
    bySlot: {
      lunch: { location: "office 3 days/week", format: "packable", constraints: ["no strong smells"] },
      dinner: { location: "home", format: "family table", constraints: [] },
    },
  },
  seasonalPreferences: {
    bySeason: {
      summer: { leanToward: ["salads", "grilled"], avoid: ["heavy stews"] },
      winter: { leanToward: ["stews", "traybakes"], avoid: [] },
    },
  },
  mealTypePreferences: {
    byType: {
      breakfast: {
        varietyTolerance: "low",
        complexityTolerance: "minimal",
        staples: ["porridge", "eggs"],
        notes: "same is fine",
      },
      dinner: {
        varietyTolerance: "high",
        complexityTolerance: "moderate",
        staples: [],
        notes: null,
      },
    },
  },
  accompaniments: {
    beverages: { withMeals: "water or sparkling", morning: "coffee, two max", avoids: ["sugary drinks"] },
    sides: { notes: "green veg as the default side" },
  },
  groceryQualityPreferences: {
    organic: "when_price_comparable",
    freeRangeEggs: "always",
    freeRangeMeat: "preferred",
    brandedVsOwnLabel: "own_label_default",
    notes: "Splurge on parmesan.",
  },
  pantryTracking: { enabled: true },
};

const lifestyleSeed: LifestyleConfigDto = {
  id: "lifestyle-config-0001",
  userId: MOCK_USER_ID,
  document: lifestyleDocumentSeed,
  // Non-null → the "is this still accurate?" review nudge shows (§5a).
  lastReviewPromptAt: at("2026-06-08", "09:00"),
  optimisticVersion: 3,
  createdAt: at("2026-04-12", "10:06"),
  updatedAt: at("2026-05-27", "20:30"),
};

const lifestyleAuditSeed: LifestyleConfigAuditEntryDto[] = [
  {
    id: "lca-3",
    actorUserId: MOCK_USER_ID,
    fieldPath: "noveltyTolerance",
    previousValueJson: { bySlot: { dinner: { mode: "rotation", rotationSize: 8 } } },
    newValueJson: { bySlot: { dinner: { mode: "high_variety", weeklyUniqueMinimum: 4, newPerWeek: 1 } } },
    occurredAt: at("2026-05-27", "20:30"),
  },
  {
    id: "lca-2",
    actorUserId: MOCK_USER_ID,
    fieldPath: "batchCooking",
    previousValueJson: { prepDays: [] },
    newValueJson: { prepDays: [{ day: "sunday", window: "15:00-18:00" }] },
    occurredAt: at("2026-05-27", "20:28"),
  },
  {
    id: "lca-1",
    actorUserId: MOCK_USER_ID,
    fieldPath: "mealTiming",
    previousValueJson: { preferredSchedule: { times: { dinner: "18:30-19:00" } } },
    newValueJson: { preferredSchedule: { times: { dinner: "19:00-19:45" } } },
    occurredAt: at("2026-04-20", "19:05"),
  },
];

/* ==== archive (preferences.md §6) ================================================== */

const archiveRow = (args: {
  n: number;
  fieldPath: string;
  itemKey: string;
  payload: Record<string, unknown>;
  evidence: number;
  lastSignalAt: string | null;
  archivedAt: string;
  reason: PreferenceArchiveEntryDto["archivedReason"];
  rePromotedAt?: string | null;
}): PreferenceArchiveEntryDto => ({
  id: `arch-${args.n}`,
  userId: MOCK_USER_ID,
  fieldPath: args.fieldPath,
  itemKey: args.itemKey,
  itemPayload: args.payload,
  evidenceCount: args.evidence,
  lastSignalAt: args.lastSignalAt,
  archivedAt: args.archivedAt,
  archivedReason: args.reason,
  rePromotedAt: args.rePromotedAt ?? null,
});

const archiveSeed: PreferenceArchiveEntryDto[] = [
  archiveRow({
    n: 5, fieldPath: "recipesToRepeat", itemKey: "winter minestrone",
    payload: { name: "Winter minestrone", reason: "cold-month favourite" },
    evidence: 6, lastSignalAt: "2026-01-30", archivedAt: at("2026-05-26", "18:41"),
    reason: "TOKEN_PRESSURE",
  }),
  archiveRow({
    n: 4, fieldPath: "ingredientPreferences.favourites", itemKey: "kimchi",
    payload: { item: "kimchi", evidenceCount: 4 },
    evidence: 4, lastSignalAt: "2026-05-24", archivedAt: at("2026-03-02", "06:00"),
    reason: "LOW_EVIDENCE", rePromotedAt: at("2026-05-25", "06:00"),
  }),
  archiveRow({
    n: 3, fieldPath: "ingredientPreferences.favourites", itemKey: "fennel",
    payload: { item: "fennel", evidenceCount: 2 },
    evidence: 2, lastSignalAt: "2026-02-10", archivedAt: at("2026-04-14", "06:00"),
    reason: "LOW_EVIDENCE",
  }),
  archiveRow({
    n: 2, fieldPath: "ingredientPreferences.disliked", itemKey: "okra",
    payload: { item: "okra", evidenceCount: 3 },
    evidence: 3, lastSignalAt: "2026-01-08", archivedAt: at("2026-04-14", "06:00"),
    reason: "STALE",
  }),
  archiveRow({
    n: 1, fieldPath: "cuisinePreferences.enjoys", itemKey: "thai",
    payload: { cuisine: "thai" },
    evidence: 5, lastSignalAt: "2025-12-19", archivedAt: at("2026-03-22", "06:00"),
    reason: "STALE",
  }),
];

export function createPreferencesSeed(): PreferencesState {
  return {
    tasteProfile: tasteProfileSeed,
    versions: tasteVersionsSeed,
    tasteAudit: tasteAuditSeed,
    refreshing: false,
    hardConstraints: hardConstraintsSeed,
    hardAudit: hardAuditSeed,
    lifestyle: lifestyleSeed,
    lifestyleAudit: lifestyleAuditSeed,
    archive: archiveSeed,
  };
}

/* ==== feedback history (activity.md §4) ============================================ */

const entry = (args: {
  id: string;
  text: string;
  context: FeedbackEntryDto["context"];
  status: FeedbackEntryDto["submissionStatus"];
  attempts: number;
  routes: FeedbackEntryDto["routes"];
  clarificationId?: string | null;
  createdAt: string;
  lastClassifiedAt?: string | null;
  updatedAt?: string;
}): FeedbackEntryDto => ({
  id: args.id,
  userId: MOCK_USER_ID,
  text: args.text,
  context: args.context,
  submissionStatus: args.status,
  classificationAttempts: args.attempts,
  lastClassifiedAt: args.lastClassifiedAt ?? args.createdAt,
  traceId: `trace-${args.id}`,
  routes: args.routes,
  pendingClarificationQueryId: args.clarificationId ?? null,
  createdAt: args.createdAt,
  updatedAt: args.updatedAt ?? args.createdAt,
});

const feedbackSeedRows: FeedbackEntryDto[] = [
  // <0.5 fragment ⇒ the WHOLE entry pauses: no route rows, status
  // CLARIFICATION_PENDING, pendingClarificationQueryId set (spec §4b).
  entry({
    id: "fb-301",
    text: "The portions have been small all week, and could we get more veg in",
    context: { screen: "PLAN_VIEW", planId: "plan-w24-g3" },
    status: "CLARIFICATION_PENDING",
    attempts: 1,
    routes: [],
    clarificationId: "cq-401",
    createdAt: at("2026-06-09", "21:10"),
  }),
  // AUTO_ROUTED to recipe; destination produced a pending change (§3 card).
  entry({
    id: "fb-302",
    text: "The stir fry was way too salty tonight",
    context: { screen: "RECIPE_DETAIL", recipeId: "chicken-stir-fry", recipeVersion: 3 },
    status: "ROUTED",
    attempts: 1,
    routes: [
      {
        id: "rt-302a",
        destination: "RECIPE",
        confidence: 0.92,
        decision: "AUTO_ROUTED",
        status: "AWAITING_USER_APPROVAL",
        extractedFeedback: "way too salty",
        actionTaken:
          "Proposed adaptation to Chicken stir-fry — reduce soy sauce by a third (awaiting your approval)",
        destinationResult: { pendingChangeId: "pc-1" },
        failureMessage: null,
      },
    ],
    createdAt: at("2026-06-09", "19:40"),
  }),
  // ROUTED_WITH_FLAG row — "I think you meant X, correct me if wrong".
  entry({
    id: "fb-303",
    text: "Loved the shakshuka, would happily have it every week",
    context: { screen: "GENERAL" },
    status: "ROUTED",
    attempts: 1,
    routes: [
      {
        id: "rt-303a",
        destination: "PREFERENCE",
        confidence: 0.91,
        decision: "AUTO_ROUTED",
        status: "APPLIED",
        extractedFeedback: "loved the shakshuka",
        actionTaken: "Logged as a strong like — shakshuka weighted up in future plans",
        destinationResult: { deltaApplied: true },
        failureMessage: null,
      },
      {
        id: "rt-303b",
        destination: "RECIPE",
        confidence: 0.62,
        decision: "ROUTED_WITH_FLAG",
        status: "APPLIED",
        extractedFeedback: "would happily have it every week",
        actionTaken: "Added shakshuka to your repeat list",
        destinationResult: null,
        failureMessage: null,
      },
    ],
    createdAt: at("2026-06-07", "09:55"),
  }),
  // Partial success — each destination write is its own transaction (§1).
  entry({
    id: "fb-304",
    text: "Running low on olive oil, and the traybake was great",
    context: { screen: "GROCERY" },
    status: "PARTIALLY_FAILED",
    attempts: 1,
    routes: [
      {
        id: "rt-304a",
        destination: "PREFERENCE",
        confidence: 0.88,
        decision: "AUTO_ROUTED",
        status: "APPLIED",
        extractedFeedback: "the traybake was great",
        actionTaken: "Traybake method weighted up",
        destinationResult: null,
        failureMessage: null,
      },
      {
        id: "rt-304b",
        destination: "PROVISIONS",
        confidence: 0.83,
        decision: "AUTO_ROUTED",
        status: "FAILED",
        extractedFeedback: "running low on olive oil",
        actionTaken: null,
        destinationResult: null,
        failureMessage: "No pantry item matched “olive oil” — add it to the pantry to track levels",
      },
    ],
    createdAt: at("2026-06-06", "17:20"),
  }),
  // Corrected mis-route: original row CORRECTED_AWAY + synchronous replay row.
  entry({
    id: "fb-305",
    text: "Friday felt rushed, dinner took way too long to cook",
    context: { screen: "PLAN_VIEW", planId: "plan-w23-g1" },
    status: "CORRECTED",
    attempts: 1,
    routes: [
      {
        id: "rt-305a",
        destination: "NUTRITION",
        confidence: 0.71,
        decision: "ROUTED_WITH_FLAG",
        status: "CORRECTED_AWAY",
        extractedFeedback: "dinner took way too long",
        actionTaken: "Adjusted dinner energy split — flagged for review",
        destinationResult: null,
        failureMessage: null,
      },
      {
        id: "rt-305b",
        destination: "PREFERENCE",
        confidence: 0.99,
        decision: "AUTO_ROUTED",
        status: "REPLAYED",
        extractedFeedback: "dinner took way too long",
        actionTaken: "Re-routed by your correction — weeknight cooking-time cap noted",
        destinationResult: null,
        failureMessage: null,
      },
    ],
    createdAt: at("2026-06-04", "21:30"),
    updatedAt: at("2026-06-05", "08:12"),
  }),
  // Resolved after a clarification round (attempts 2; answered query cq-402).
  entry({
    id: "fb-306",
    text: "Less spicy for the kids please",
    context: { screen: "PLAN_MEAL_DETAIL", planId: "plan-w22-g1", mealSlotId: "slot-w22-thu-dinner" },
    status: "ROUTED",
    attempts: 2,
    routes: [
      {
        id: "rt-306a",
        destination: "PREFERENCE",
        confidence: 0.97,
        decision: "AUTO_ROUTED",
        status: "APPLIED",
        extractedFeedback: "less spicy for the kids",
        actionTaken: "Household note added — kids' plates split before chilli",
        destinationResult: null,
        failureMessage: null,
      },
    ],
    createdAt: at("2026-05-28", "18:05"),
    lastClassifiedAt: at("2026-05-29", "07:40"),
    updatedAt: at("2026-05-29", "07:40"),
  }),
  // Clarification expired unanswered → classifier gave up (410 path, §5b).
  entry({
    id: "fb-307",
    text: "More fish maybe? not sure about the mackerel though",
    context: { screen: "GENERAL" },
    status: "FAILED",
    attempts: 1,
    routes: [],
    createdAt: at("2026-05-22", "12:30"),
    updatedAt: at("2026-05-25", "12:30"),
  }),
];

/* ==== clarifications inbox (activity.md §5) ======================================== */

const clarificationsSeed: ClarificationQueryDto[] = [
  {
    id: "cq-401",
    feedbackEntryId: "fb-301",
    textExcerpt: "The portions have been small all week, and could we get more veg in",
    questionText:
      "When you say “more veg” — is that dinner specifically, or across the whole day?",
    options: [
      {
        destination: "NUTRITION",
        snippet: "could we get more veg in",
        classifierJustification:
          "Could be a daily target change — veg servings across the day",
      },
      {
        destination: "PREFERENCE",
        snippet: "more veg",
        classifierJustification:
          "Could be a taste-profile lean toward vegetable-forward dinners",
      },
    ],
    status: "PENDING",
    expiresAt: at("2026-06-12", "21:11"),
    createdAt: at("2026-06-09", "21:11"),
  },
  {
    id: "cq-402",
    feedbackEntryId: "fb-306",
    textExcerpt: "Less spicy for the kids please",
    questionText: "Less spicy everywhere, or just on the kids' plates?",
    options: [
      { destination: "PREFERENCE", snippet: "less spicy", classifierJustification: null },
      {
        destination: "RECIPE",
        snippet: "less spicy for the kids",
        classifierJustification: "Could be a one-recipe adaptation",
      },
    ],
    status: "ANSWERED",
    expiresAt: at("2026-05-31", "18:06"),
    createdAt: at("2026-05-28", "18:06"),
  },
  {
    id: "cq-403",
    feedbackEntryId: "fb-307",
    textExcerpt: "More fish maybe? not sure about the mackerel though",
    questionText: "Is “more fish” a preference lean, or a nutrition goal (omega-3)?",
    options: [
      { destination: "PREFERENCE", snippet: "more fish maybe", classifierJustification: null },
      { destination: "NUTRITION", snippet: "more fish", classifierJustification: null },
    ],
    status: "EXPIRED",
    expiresAt: at("2026-05-25", "12:31"),
    createdAt: at("2026-05-22", "12:31"),
  },
];

/* ==== corrections log (activity.md §4c) ============================================ */

const correctionsSeed: MisclassificationCorrectionDto[] = [
  {
    id: "corr-502",
    feedbackEntryId: "fb-305",
    textExcerpt: "Friday felt rushed, dinner took way too long to cook",
    originalRoutingId: "rt-305a",
    correctedDestination: "PREFERENCE",
    originalDestination: "NUTRITION",
    originalConfidence: 0.71,
    userCorrectionNote: "It's about cooking time, not calories",
    actorUserId: MOCK_USER_ID,
    replayRoutingId: "rt-305b",
    replayStatus: "APPLIED",
    occurredAt: at("2026-06-05", "08:12"),
    createdAt: at("2026-06-05", "08:12"),
  },
  {
    id: "corr-501",
    feedbackEntryId: "fb-290",
    textExcerpt: "Out of olive oil again by Thursday",
    originalRoutingId: "rt-290a",
    correctedDestination: "PROVISIONS",
    originalDestination: "RECIPE",
    originalConfidence: 0.55,
    userCorrectionNote: "I meant we ran out of it",
    actorUserId: MOCK_USER_ID,
    replayRoutingId: null,
    replayStatus: "DESTINATION_REJECTED",
    occurredAt: at("2026-05-19", "07:30"),
    createdAt: at("2026-05-19", "07:30"),
  },
];

export function createActivitySeed(): ActivityState {
  return {
    feedback: feedbackSeedRows,
    clarifications: clarificationsSeed,
    corrections: correctionsSeed,
    composePrefill: null,
  };
}
