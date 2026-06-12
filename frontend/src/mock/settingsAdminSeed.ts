/**
 * Seeds for the final five page rebuilds (notifications.md, settings.md,
 * login.md, onboarding.md, admin.md) — production contract DTOs throughout.
 * Mock "today" is Wednesday 10 June 2026 18:00Z (MOCK_NOW_MS); relative
 * timestamps key off it.
 */

import { MOCK_USER_ID } from "./nutritionSeed";
import { HOUSEHOLD_ID } from "./plannerSeed";
import type {
  AdminState,
  AiCallLogDto,
  AnyNotificationKind,
  DecisionLogDto,
  DeliveryLogEntryDto,
  HouseholdDto,
  HouseholdSettingsDto,
  HouseholdState,
  MockNotificationDto,
  NotificationPreferenceDto,
  NotificationsState,
  PlannerDecisionChainDto,
  PromptTemplateDto,
  SessionState,
  SlotConfigurationDto,
  SlotKind,
} from "./types";

export const MOCK_NOW_MS = Date.parse("2026-06-10T18:00:00Z");

const at = (date: string, time: string): string => `${date}T${time}:00Z`;

/* ==== notifications (notifications.md §3) =========================================== */

/** All ten Java kinds — the OpenAPI enum only carries eight (spec §8 Q1). */
export const ALL_NOTIFICATION_KINDS: AnyNotificationKind[] = [
  "PROVISION_ITEM_NEAR_EXPIRY",
  "PROVISION_ITEM_SPOILED",
  "PROVISION_DEFROST_REMINDER",
  "NUTRITION_INTAKE_DIVERGED",
  "HEALTH_DIRECTIVE_RECEIVED",
  "PLANNER_PREP_REMINDER",
  "PLANNER_REOPT_SUGGESTED",
  "PLANNER_PLAN_GENERATED",
  "STAPLE_REPLENISHMENT_NEEDED",
  "FEEDBACK_CONFIRMATION",
];

interface RowArgs {
  n: number;
  kind: AnyNotificationKind;
  severity: MockNotificationDto["severity"];
  title: string;
  body: string;
  status: MockNotificationDto["status"];
  createdAt: string;
  actionTargetUri?: string | null;
  bundleCount?: number;
  readAt?: string | null;
  actionedAt?: string | null;
  dismissedAt?: string | null;
  traceId?: string | null;
}

const row = (a: RowArgs): MockNotificationDto => ({
  id: `ntf-${a.n}`,
  userId: MOCK_USER_ID,
  householdId: HOUSEHOLD_ID,
  kind: a.kind,
  severity: a.severity,
  title: a.title,
  body: a.body,
  payload: { kind: a.kind },
  status: a.status,
  actionTargetUri: a.actionTargetUri ?? null,
  bundleCount: a.bundleCount ?? 1,
  bundleKeys: null,
  traceId: a.traceId ?? null,
  createdAt: a.createdAt,
  readAt: a.readAt ?? null,
  actionedAt: a.actionedAt ?? null,
  dismissedAt: a.dismissedAt ?? null,
  version: 0,
});

/** Inbox rows, newest first — covers all severities, bundling, every status,
 *  and one row of each contract-missing kind (the §8 Q1 enum gap, footnoted). */
const notificationRowsSeed: MockNotificationDto[] = [
  row({
    n: 11,
    kind: "NUTRITION_INTAKE_DIVERGED",
    severity: "INFO",
    title: "Lunch drifted from plan three times this week",
    body: "Logged intake diverged from the planned lunch on Mon, Tue and today — mostly extra carbs. Review the intake page if this should nudge your targets.",
    status: "UNREAD",
    createdAt: at("2026-06-10", "12:40"),
    actionTargetUri: "/app/nutrition/intake/2026-06-10",
    bundleCount: 3,
  }),
  row({
    n: 10,
    kind: "STAPLE_REPLENISHMENT_NEEDED",
    severity: "INFO",
    title: "Olive oil is running low",
    body: "The staple scanner marked olive oil LOW after Tuesday's batch cook. It has been added to the next shopping list as a replenishment line.",
    status: "UNREAD",
    createdAt: at("2026-06-10", "08:05"),
    actionTargetUri: "/app/provisions/inventory",
  }),
  row({
    n: 9,
    kind: "PROVISION_ITEM_SPOILED",
    severity: "URGENT",
    title: "Spinach marked spoiled — Thursday's curry is affected",
    body: "The bag of spinach in the fridge passed its use-by and was marked spoiled. One future slot uses it; a re-optimisation suggestion is waiting on the plan page.",
    status: "UNREAD",
    createdAt: at("2026-06-10", "07:10"),
    actionTargetUri: "/app/provisions/inventory",
  }),
  row({
    n: 8,
    kind: "PROVISION_DEFROST_REMINDER",
    severity: "ATTENTION",
    title: "Defrost chicken thighs for tomorrow's traybake",
    body: "Thursday dinner needs the chicken thighs out of the freezer and into the fridge by 07:00 tomorrow for a safe slow defrost.",
    status: "UNREAD",
    createdAt: at("2026-06-10", "06:00"),
    actionTargetUri: "/app/provisions/inventory",
  }),
  row({
    n: 7,
    kind: "PLANNER_REOPT_SUGGESTED",
    severity: "ATTENTION",
    title: "Plan fix suggested — swap Thursday's curry",
    body: "Spinach spoilage makes Thursday's curry infeasible. A one-slot swap to the freezer chilli keeps the week within budget.",
    status: "UNREAD",
    createdAt: at("2026-06-09", "23:40"),
    actionTargetUri: "/app/plans/plan-w24-g3",
    traceId: "trace-aaaa-0001",
  }),
  row({
    n: 6,
    kind: "FEEDBACK_CONFIRMATION",
    severity: "INFO",
    title: "Your feedback was applied — stir-fry salt reduced",
    body: "“Too salty” routed to the recipe module; the chicken stir-fry now uses 1 tbsp less soy sauce. Undo from the recipe's version history.",
    status: "READ",
    createdAt: at("2026-06-09", "20:11"),
    readAt: at("2026-06-09", "21:30"),
    actionTargetUri: "/app/feedback/fb-301",
  }),
  row({
    n: 5,
    kind: "PROVISION_ITEM_NEAR_EXPIRY",
    severity: "ATTENTION",
    title: "Greek yoghurt expires in 2 days",
    body: "The open tub in the fridge expires Friday. Friday breakfast already uses it — no action needed if the plan holds.",
    status: "READ",
    createdAt: at("2026-06-09", "18:00"),
    readAt: at("2026-06-09", "19:05"),
    actionTargetUri: "/app/provisions/inventory",
  }),
  row({
    n: 4,
    kind: "HEALTH_DIRECTIVE_RECEIVED",
    severity: "URGENT",
    title: "Health directive received — review before it applies",
    body: "A connected health platform sent a low-sodium directive. Nothing changes until you accept, modify or reject it on the nutrition page.",
    status: "ACTIONED",
    createdAt: at("2026-06-09", "08:30"),
    readAt: at("2026-06-09", "08:55"),
    actionedAt: at("2026-06-09", "09:01"),
    actionTargetUri: "/app/nutrition/health-directives/hd-0001",
  }),
  row({
    n: 3,
    kind: "PLANNER_PLAN_GENERATED",
    severity: "INFO",
    title: "Generation 3 is ready for review",
    body: "Tuesday's re-optimisation produced generation 3 for the week of 8 June. It is waiting for your approval on the plan page.",
    status: "ACTIONED",
    createdAt: at("2026-06-09", "07:45"),
    readAt: at("2026-06-09", "07:50"),
    actionedAt: at("2026-06-09", "07:52"),
    actionTargetUri: "/app/plans/plan-w24-g3",
    traceId: "trace-aaaa-0001",
  }),
  row({
    n: 2,
    kind: "PLANNER_PREP_REMINDER",
    severity: "INFO",
    title: "Tonight's dinner has a 20-minute prep window",
    body: "Salmon traybake serves at 18:30 — start prep by 17:40 to stay inside the slot's time budget.",
    status: "READ",
    createdAt: at("2026-06-08", "17:00"),
    readAt: at("2026-06-08", "17:12"),
    actionTargetUri: "/app/planner/slots/plan-w24-g3-2026-06-08-dinner",
  }),
  row({
    n: 1,
    kind: "PROVISION_ITEM_NEAR_EXPIRY",
    severity: "INFO",
    title: "Milk expired Monday",
    body: "The fridge milk passed its date on Monday. It was marked exhausted when the shopping list was fulfilled.",
    status: "DISMISSED",
    createdAt: at("2026-06-08", "06:30"),
    readAt: at("2026-06-08", "08:00"),
    dismissedAt: at("2026-06-08", "08:01"),
    actionTargetUri: "/app/provisions/inventory",
  }),
];

const dl = (
  n: number,
  notificationId: string,
  channel: DeliveryLogEntryDto["channel"],
  outcome: DeliveryLogEntryDto["outcome"],
  attemptedAt: string,
  skipReason: DeliveryLogEntryDto["skipReason"] = null,
): DeliveryLogEntryDto => ({
  id: `dlv-${n}`,
  notificationId,
  channel,
  outcome,
  skipReason,
  attemptedAt,
});

/** Per-notification delivery log (#8), newest first — one row per attempt:
 *  DELIVERED, DEFERRED (quiet hours), SKIPPED (pref-muted / deduped), FAILED. */
const deliveryLogSeed: Record<string, DeliveryLogEntryDto[]> = {
  "ntf-11": [
    dl(7, "ntf-11", "IN_APP", "SKIPPED", at("2026-06-10", "13:02"), "DEDUPED_INTO_BUNDLE"),
    dl(6, "ntf-11", "IN_APP", "SKIPPED", at("2026-06-10", "12:51"), "DEDUPED_INTO_BUNDLE"),
    dl(5, "ntf-11", "IN_APP", "DELIVERED", at("2026-06-10", "12:40")),
  ],
  "ntf-9": [
    dl(9, "ntf-9", "IN_APP", "DELIVERED", at("2026-06-10", "07:10")),
    dl(8, "ntf-9", "EMAIL", "FAILED", at("2026-06-10", "07:10")),
  ],
  "ntf-8": [dl(10, "ntf-8", "IN_APP", "DELIVERED", at("2026-06-10", "06:00"))],
  "ntf-7": [
    dl(12, "ntf-7", "IN_APP", "DELIVERED", at("2026-06-10", "07:00")),
    dl(11, "ntf-7", "IN_APP", "DEFERRED", at("2026-06-09", "23:40"), "QUIET_HOURS"),
  ],
  "ntf-4": [
    dl(14, "ntf-4", "IN_APP", "DELIVERED", at("2026-06-09", "08:30")),
    dl(13, "ntf-4", "PUSH", "SKIPPED", at("2026-06-09", "08:30"), "CHANNEL_UNAVAILABLE"),
  ],
  "ntf-3": [
    dl(15, "ntf-3", "IN_APP", "SKIPPED", at("2026-06-09", "07:45"), "DISABLED_BY_PREF"),
  ],
};

/** Preferences row (#9) — PLANNER_PLAN_GENERATED seeds OFF (default-OFF kind),
 *  quiet hours 22:00–07:00 wrapping midnight, Europe/London, debounce 30. */
const notificationPrefsSeed: NotificationPreferenceDto = {
  id: "ntf-pref-0001",
  userId: MOCK_USER_ID,
  enabledKinds: Object.fromEntries(
    ALL_NOTIFICATION_KINDS.map((k) => [k, k !== "PLANNER_PLAN_GENERATED"]),
  ),
  quietHoursEnabled: true,
  quietHoursStart: "22:00",
  quietHoursEnd: "07:00",
  timezone: "Europe/London",
  debounceWindowMinutes: 30,
  version: 4,
};

export function createNotificationsSeed(): NotificationsState {
  return {
    rows: notificationRowsSeed,
    prefs: notificationPrefsSeed,
    deliveryLog: deliveryLogSeed,
  };
}

/* ==== household (settings.md §3) ===================================================== */

/** m4 deliberately has no displayName — renders as the userId stub the spec
 *  footnotes (settings.md §8 Q2: no username join anywhere). */
const householdSeed: HouseholdDto = {
  id: HOUSEHOLD_ID,
  name: "Veer household",
  createdByUserId: MOCK_USER_ID,
  createdAt: at("2026-04-12", "10:02"),
  version: 3,
  members: [
    {
      id: "m1",
      householdId: HOUSEHOLD_ID,
      userId: MOCK_USER_ID,
      role: "primary",
      displayName: "Iren",
      priority: 100,
      joinedAt: at("2026-04-12", "10:02"),
      version: 2,
    },
    {
      id: "m2",
      householdId: HOUSEHOLD_ID,
      userId: "user-sam-0002",
      role: "member",
      displayName: "Sam",
      priority: 80,
      joinedAt: at("2026-04-14", "19:21"),
      version: 1,
    },
    {
      id: "m3",
      householdId: HOUSEHOLD_ID,
      userId: "user-maya-0003",
      role: "member",
      displayName: "Maya",
      priority: 60,
      joinedAt: at("2026-04-20", "08:40"),
      version: 1,
    },
    {
      id: "m4",
      householdId: HOUSEHOLD_ID,
      userId: "user-theo-0004",
      role: "member",
      displayName: null,
      priority: 60,
      joinedAt: at("2026-04-20", "08:44"),
      version: 0,
    },
  ],
};

const settingsSeed: HouseholdSettingsDto = {
  id: "hh-settings-0001",
  householdId: HOUSEHOLD_ID,
  document: {
    slotDefaults: {
      breakfast: { shared: false, headcount: null, timeBudgetMin: null },
      lunch: { shared: false, headcount: null, timeBudgetMin: 20 },
      dinner: { shared: true, headcount: 4, timeBudgetMin: 45 },
      snack: { shared: false, headcount: null, timeBudgetMin: null },
    },
    customSlots: [
      {
        key: "post-workout-shake",
        label: "Post-workout shake",
        backedByKind: "snack",
        shared: false,
        headcount: null,
        timeBudgetMin: 5,
      },
    ],
    defaultHeadcount: 4,
  },
  version: 5,
  createdAt: at("2026-04-12", "10:02"),
};

/** Per-kind time-budget defaults applied at resolve time (meal-planner.md). */
export const KIND_TIME_DEFAULT: Record<SlotKind, number> = {
  breakfast: 15,
  lunch: 20,
  dinner: 45,
  snack: 5,
  custom: 30,
};

/** Mirror of the server's settings→planner resolution (#6 read-back): fills
 *  nullable headcount/timeBudget from defaults, expands per-person eaters. */
export function resolveSlotConfiguration(
  household: HouseholdDto,
  settings: HouseholdSettingsDto,
): SlotConfigurationDto {
  const allEaterUserIds = household.members.map((m) => m.userId);
  const doc = settings.document;
  const entry = (
    slotKey: string,
    kind: SlotKind,
    d: { shared: boolean; headcount?: number | null; timeBudgetMin?: number | null },
  ) => ({
    slotKey,
    kind,
    shared: d.shared,
    headcount: d.headcount ?? doc.defaultHeadcount ?? household.members.length,
    timeBudgetMin: d.timeBudgetMin ?? KIND_TIME_DEFAULT[kind],
    eaterUserIdsIfPerPerson: d.shared ? null : allEaterUserIds,
  });
  return {
    householdId: household.id,
    slots: [
      ...Object.entries(doc.slotDefaults).map(([key, d]) =>
        entry(key, (key in KIND_TIME_DEFAULT ? key : "custom") as SlotKind, d),
      ),
      ...doc.customSlots.map((c) => entry(c.key, c.backedByKind, c)),
    ],
    allEaterUserIds,
  };
}

export function createHouseholdSeed(): HouseholdState {
  return {
    current: householdSeed,
    settings: settingsSeed,
    settingsAudit: [
      {
        id: "hsa-3",
        actorUserId: MOCK_USER_ID,
        fieldPath: "slotDefaults.dinner.timeBudgetMin",
        previousValue: 40,
        newValue: 45,
        occurredAt: at("2026-06-02", "19:30"),
      },
      {
        id: "hsa-2",
        actorUserId: MOCK_USER_ID,
        fieldPath: "customSlots[post-workout-shake]",
        previousValue: null,
        newValue: { label: "Post-workout shake", backedByKind: "snack" },
        occurredAt: at("2026-05-18", "07:55"),
      },
      {
        id: "hsa-1",
        actorUserId: MOCK_USER_ID,
        fieldPath: "slotDefaults.dinner.shared",
        previousValue: false,
        newValue: true,
        occurredAt: at("2026-04-12", "10:05"),
      },
    ],
    resolved: resolveSlotConfiguration(householdSeed, settingsSeed),
    invites: [
      {
        id: "inv-2001",
        householdId: HOUSEHOLD_ID,
        inviteCode: null, // redacted on list responses — 201-only reveal (§3b)
        issuedByUserId: MOCK_USER_ID,
        issuedForUserId: null,
        intendedRole: "member",
        expiresAt: at("2026-06-15", "12:00"),
        acceptedAt: null,
        revokedAt: null,
        status: "PENDING",
      },
      {
        id: "inv-2002",
        householdId: HOUSEHOLD_ID,
        inviteCode: null,
        issuedByUserId: MOCK_USER_ID,
        issuedForUserId: "user-grandma-0005", // restricted invite — 403 for anyone else
        intendedRole: "member",
        expiresAt: at("2026-06-13", "09:00"),
        acceptedAt: null,
        revokedAt: null,
        status: "PENDING",
      },
    ],
    // Server-side secret: codes are only ever revealed on the 201 create
    // response; this map lets /invite redeem the seeded ones.
    inviteCodes: {
      "inv-2001": "MP-7TQK-4Y2N",
      "inv-2002": "MP-403X-DEMO",
    },
  };
}

/* ==== session (login.md) ============================================================= */

export function createSessionSeed(): SessionState {
  return {
    // The mock boots signed in (the /auth/me probe would 200) so every page
    // stays directly reachable; logout exercises the guard redirect.
    user: { userId: MOCK_USER_ID, username: "iren" },
    lockedUntilMs: null,
    lockKind: null,
    failedAttempts: 0,
    freshSetup: null,
  };
}

/* ==== admin (admin.md §3) ============================================================ */

const call = (a: {
  n: number;
  userId?: string | null;
  taskType: AiCallLogDto["taskType"];
  tier: AiCallLogDto["modelTier"];
  modelId: string;
  status: AiCallLogDto["status"];
  errorKind?: AiCallLogDto["errorKind"];
  reqTok?: number | null;
  resTok?: number | null;
  costMicroPence: number;
  latencyMs?: number | null;
  createdAt: string;
  completedAt?: string | null;
  promptRefName?: string | null;
  promptRefVersion?: number | null;
  traceId?: string | null;
}): AiCallLogDto => ({
  id: `ai-call-${a.n}`,
  userId: a.userId === undefined ? MOCK_USER_ID : a.userId,
  traceId: a.traceId ?? null,
  taskType: a.taskType,
  modelTier: a.tier,
  modelId: a.modelId,
  promptRefName: a.promptRefName ?? null,
  promptRefVersion: a.promptRefVersion ?? null,
  requestTokens: a.reqTok ?? null,
  responseTokens: a.resTok ?? null,
  costMicroPence: a.costMicroPence,
  status: a.status,
  errorKind: a.errorKind ?? null,
  latencyMs: a.latencyMs ?? null,
  createdAt: a.createdAt,
  completedAt: a.completedAt ?? null,
});

/** Call log, newest first — mixed tiers/statuses; costs are integer
 *  micro-pence (£ = µp ÷ 100 000 000 — admin.md §7 Q1). */
const callLogSeed: AiCallLogDto[] = [
  call({
    n: 9, taskType: "FEEDBACK_CLASSIFICATION", tier: "CHEAP", modelId: "gpt-4.1-mini",
    status: "PENDING", costMicroPence: 0, reqTok: 412, createdAt: at("2026-06-10", "17:42"),
    promptRefName: "feedback-classification", promptRefVersion: 3,
  }),
  call({
    n: 8, taskType: "PLANNER_STAGE_C", tier: "HIGH", modelId: "claude-sonnet-4-5",
    status: "SUCCEEDED", costMicroPence: 41_280_000, reqTok: 6_840, resTok: 1_212,
    latencyMs: 9_412, createdAt: at("2026-06-10", "16:05"), completedAt: at("2026-06-10", "16:05"),
    promptRefName: "planner-stage-c", promptRefVersion: 5, traceId: "trace-aaaa-0001",
  }),
  call({
    n: 7, taskType: "INTAKE_PARSE", tier: "CHEAP", modelId: "gpt-4.1-mini",
    status: "SUCCEEDED", costMicroPence: 612_000, reqTok: 220, resTok: 64,
    latencyMs: 980, createdAt: at("2026-06-10", "12:41"), completedAt: at("2026-06-10", "12:41"),
  }),
  call({
    n: 6, taskType: "RECIPE_ADAPTATION", tier: "MID", modelId: "claude-haiku-4-5",
    status: "FAILED", errorKind: "AI_UNAVAILABLE", costMicroPence: 0,
    latencyMs: 30_021, createdAt: at("2026-06-10", "09:18"), completedAt: at("2026-06-10", "09:19"),
    traceId: "trace-bbbb-0002",
  }),
  call({
    n: 5, taskType: "RECIPE_ADAPTATION", tier: "MID", modelId: "claude-haiku-4-5",
    status: "SUCCEEDED", costMicroPence: 8_140_000, reqTok: 2_410, resTok: 510,
    latencyMs: 4_206, createdAt: at("2026-06-10", "09:21"), completedAt: at("2026-06-10", "09:21"),
    traceId: "trace-bbbb-0002",
  }),
  call({
    n: 4, userId: "user-sam-0002", taskType: "FEEDBACK_CLASSIFICATION", tier: "CHEAP",
    modelId: "gpt-4.1-mini", status: "SUCCEEDED", costMicroPence: 390_000,
    reqTok: 388, resTok: 71, latencyMs: 1_120, createdAt: at("2026-06-09", "20:10"),
    completedAt: at("2026-06-09", "20:10"), promptRefName: "feedback-classification",
    promptRefVersion: 3,
  }),
  call({
    n: 3, taskType: "INGREDIENT_MAPPING", tier: "CHEAP", modelId: "gpt-4.1-mini",
    status: "SUCCEEDED", costMicroPence: 240_500, reqTok: 145, resTok: 38,
    latencyMs: 760, createdAt: at("2026-06-09", "12:02"), completedAt: at("2026-06-09", "12:02"),
  }),
  call({
    n: 2, userId: null, taskType: "DISCOVERY_FILTERING", tier: "CHEAP", modelId: "gpt-4.1-mini",
    status: "SUCCEEDED", costMicroPence: 1_905_000, reqTok: 3_204, resTok: 240,
    latencyMs: 2_390, createdAt: at("2026-06-08", "03:00"), completedAt: at("2026-06-08", "03:00"),
  }),
  call({
    n: 1, taskType: "PREFERENCE_DELTA_UPDATE", tier: "MID", modelId: "claude-haiku-4-5",
    status: "SUCCEEDED", costMicroPence: 5_420_000, reqTok: 1_980, resTok: 402,
    latencyMs: 3_511, createdAt: at("2026-06-08", "06:00"), completedAt: at("2026-06-08", "06:00"),
  }),
];

const promptTemplatesSeed: PromptTemplateDto[] = [
  {
    id: "pt-0001",
    name: "feedback-classification",
    version: 3,
    modelTier: "CHEAP",
    systemPrompt:
      "You are a feedback router for a meal-planning system. Classify the user's free-text feedback into exactly one destination: RECIPE, PREFERENCE, NUTRITION or PROVISIONS. Output JSON matching the schema; include a confidence in [0,1] and a one-sentence justification per candidate.",
    userPromptTemplate:
      "Feedback: {{feedbackText}}\nScreen context: {{uiContext}}\nRecent meals: {{recentMeals}}",
    outputSchema: {
      type: "object",
      required: ["destination", "confidence"],
      properties: {
        destination: { enum: ["RECIPE", "PREFERENCE", "NUTRITION", "PROVISIONS"] },
        confidence: { type: "number" },
      },
    },
    tools: null,
    notes: "v3 adds the screen-context hint after the GAP-44 confusion matrix review.",
    sourceFile: "prompts/feedback/classification.yaml",
    sourceHash: "sha256:7f3c19ab",
    createdAt: at("2026-05-02", "11:00"),
  },
  {
    id: "pt-0002",
    name: "planner-stage-c",
    version: 5,
    modelTier: "HIGH",
    systemPrompt:
      "You are the Stage-C meal-plan critic. Given a candidate week assignment and the household's merged constraints, identify the weakest slots and propose bounded swaps. Never violate hard constraints; respect the per-slot time budgets.",
    userPromptTemplate:
      "Candidate plan: {{candidateJson}}\nConstraints: {{constraintsJson}}\nBudget: {{budgetPence}}",
    outputSchema: { type: "object", properties: { swaps: { type: "array" } } },
    tools: null,
    notes: null,
    sourceFile: "prompts/planner/stage-c.yaml",
    sourceHash: "sha256:c41d22e0",
    createdAt: at("2026-05-20", "09:30"),
  },
];

const decision = (a: {
  id: string;
  traceId: string;
  parent?: string | null;
  scopeKind: string;
  scopeId: string;
  scale: DecisionLogDto["scale"];
  triggeredBy: string;
  actorUserId?: string | null;
  inputs: Record<string, unknown>;
  candidates?: Record<string, unknown> | null;
  chosen?: Record<string, unknown> | null;
  reasoning?: string | null;
  iteration?: number;
  durationMs?: number | null;
  createdAt: string;
}): DecisionLogDto => ({
  decisionId: a.id,
  traceId: a.traceId,
  parentDecisionId: a.parent ?? null,
  scopeKind: a.scopeKind,
  scopeId: a.scopeId,
  scale: a.scale,
  triggeredBy: a.triggeredBy,
  actorUserId: a.actorUserId === undefined ? MOCK_USER_ID : a.actorUserId,
  inputs: a.inputs,
  candidates: a.candidates ?? null,
  chosen: a.chosen ?? null,
  reasoning: a.reasoning ?? null,
  emittedDirective: null,
  iteration: a.iteration ?? 0,
  durationMs: a.durationMs ?? null,
  createdAt: a.createdAt,
});

const decisionsSeed: DecisionLogDto[] = [
  decision({
    id: "dcn-0100",
    traceId: "trace-aaaa-0001",
    scopeKind: "plan-week",
    scopeId: "plan-w24-g3",
    scale: "WEEK",
    triggeredBy: "reopt-accepted",
    inputs: { weekStartDate: "2026-06-08", trigger: "PROVISION_SPOILAGE" },
    candidates: { considered: 3, pruned: 1 },
    chosen: { generation: 3, swappedSlots: 1 },
    reasoning:
      "Thursday's curry lost its spinach to spoilage. A single-slot swap to the freezer chilli preserves the protein target and stays £1.40 under the weekly budget, so the wider re-shuffle was rejected as unnecessary churn.",
    durationMs: 9_412,
    createdAt: at("2026-06-09", "07:45"),
  }),
  decision({
    id: "dcn-0101",
    traceId: "trace-aaaa-0001",
    parent: "dcn-0100",
    scopeKind: "slot-assignment",
    scopeId: "plan-w24-g3-2026-06-11-dinner",
    scale: "RECIPE",
    triggeredBy: "parent-decision",
    actorUserId: null,
    inputs: { slot: "2026-06-11 dinner", excluded: ["spinach-curry"] },
    candidates: { ranked: ["freezer-chilli", "soup-bread", "pizza-night"] },
    chosen: { recipeId: "batch-chilli-base" },
    reasoning:
      "The chilli base is already cooked and frozen — zero marginal prep on a weeknight, and it consumes a batch portion that would otherwise risk freezer-burn within three weeks.",
    iteration: 1,
    durationMs: 2_106,
    createdAt: at("2026-06-09", "07:45"),
  }),
  decision({
    id: "dcn-0102",
    traceId: "trace-aaaa-0001",
    parent: "dcn-0101",
    scopeKind: "grocery-impact",
    scopeId: "shopping-list-w24",
    scale: "OTHER",
    triggeredBy: "parent-decision",
    actorUserId: null,
    inputs: { removedLines: 2, addedLines: 0 },
    chosen: { listDelta: "-£3.10" },
    reasoning:
      "Dropping the curry removes two unfulfilled lines from the open shopping list; nothing new is required because the chilli is fully stocked.",
    iteration: 2,
    durationMs: 311,
    createdAt: at("2026-06-09", "07:46"),
  }),
  decision({
    id: "dcn-0200",
    traceId: "trace-bbbb-0002",
    scopeKind: "recipe-adaptation",
    scopeId: "chicken-stir-fry",
    scale: "RECIPE",
    triggeredBy: "feedback-routed",
    inputs: { dimension: "SALT_LEVEL", feedbackId: "fb-301" },
    candidates: { options: ["reduce-soy", "swap-tamari", "no-change"] },
    chosen: { option: "reduce-soy", delta: "-1 tbsp soy sauce" },
    reasoning:
      "Two independent “too salty” signals in three weeks clear the adaptation threshold. Reducing the soy by a tablespoon keeps the glaze texture; swapping to tamari would also change the flavour profile, which nobody asked for.",
    durationMs: 4_206,
    createdAt: at("2026-06-10", "09:21"),
  }),
];

/** Planner decision chain for the active plan (#8) — createdAt ascending. */
const plannerChainSeed: PlannerDecisionChainDto = {
  planId: "plan-w24-g3",
  rows: [
    {
      decisionId: "pdr-1",
      parentDecisionId: null,
      traceId: "trace-aaaa-0001",
      kind: "STAGE_A_POOL",
      inputs: { poolSize: 38, hardConstraintFiltered: 6 },
      outputs: { feasible: 32 },
      reasoning: null,
      createdAt: at("2026-06-09", "07:44"),
    },
    {
      decisionId: "pdr-2",
      parentDecisionId: "pdr-1",
      traceId: "trace-aaaa-0001",
      kind: "STAGE_B_ASSIGN",
      inputs: { slots: 21, locked: 9 },
      outputs: { assigned: 21, score: 0.87 },
      reasoning: null,
      createdAt: at("2026-06-09", "07:44"),
    },
    {
      decisionId: "pdr-3",
      parentDecisionId: "pdr-2",
      traceId: "trace-aaaa-0001",
      kind: "STAGE_C_DONE",
      inputs: { candidateScore: 0.87 },
      outputs: { swapsProposed: 1, finalScore: 0.91 },
      reasoning:
        "One weak slot: Thursday dinner scored 0.42 on pantry-fit after the spoilage event. The chilli swap lifts it to 0.88 without touching any locked slot.",
      createdAt: at("2026-06-09", "07:45"),
    },
    {
      decisionId: "pdr-4",
      parentDecisionId: "pdr-3",
      traceId: "trace-aaaa-0001",
      kind: "PLAN_PERSISTED",
      inputs: { generation: 3 },
      outputs: { planId: "plan-w24-g3", status: "GENERATED" },
      reasoning: null,
      createdAt: at("2026-06-09", "07:45"),
    },
  ],
};

export function createAdminSeed(): AdminState {
  return {
    allowlisted: true,
    probeOutcome: null,
    status: {
      status: "UP",
      checkedAt: at("2026-06-10", "18:00"),
      dbConnected: true,
      lastAiCallAt: at("2026-06-10", "17:42"),
      lastUsdaCallAt: at("2026-06-10", "11:05"),
      aiMonthToDatePence: 412, // pence — NOT micro-pence (admin.md §7 Q1)
    },
    callLog: callLogSeed,
    promptTemplates: promptTemplatesSeed,
    decisions: decisionsSeed,
    plannerChains: { "plan-w24-g3": plannerChainSeed },
  };
}
