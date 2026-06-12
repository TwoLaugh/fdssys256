/**
 * Types for the in-memory mock store that makes the app fully playable
 * without a backend. Shapes are ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and expanded
 * where pages need more.
 *
 * The nutrition slices are the exception: they mirror the real backend DTOs
 * (re-exported from the generated OpenAPI types below) so the mock validates
 * the production contract — see design/frontend/pages/nutrition.md.
 */

import type { components } from "../api/types.gen";

/* ---- plan: backend DTO mirrors ------------------------------------------------
 * The planner slices mirror the real contract (design/frontend/pages/plan.md §2)
 * exactly like the nutrition slices below — re-exported from the generated
 * OpenAPI types so the mock validates the production shapes.
 */

export type MealSlotKey = "breakfast" | "lunch" | "dinner";

type PlannerSchemas = components["schemas"];

export type PlanStatus = PlannerSchemas["PlanStatus"];
export type TriggerKind = PlannerSchemas["TriggerKind"];
export type PlannerSlotKind = PlannerSchemas["PlannerSlotKind"];
/** Planner slot lifecycle: PLANNED → COOKING → COOKED → EATEN | SKIPPED. */
export type SlotState = PlannerSchemas["SlotState"];
export type PinnedReason = NonNullable<PlannerSchemas["MealSlotDto"]["pinnedReason"]>;
export type ReoptTriggerKind = PlannerSchemas["ReoptTriggerKind"];
export type ConflictType = PlannerSchemas["ConflictType"];

export type MealSlotDto = PlannerSchemas["MealSlotDto"];
export type ScheduledRecipeRef = NonNullable<MealSlotDto["scheduledRecipe"]>;
export type DayDto = PlannerSchemas["DayDto"];
export type PlanDto = PlannerSchemas["PlanDto"];
export type ScoreBreakdownDocument = PlannerSchemas["ScoreBreakdownDocument"];
export type RollupSummaryDocument = PlannerSchemas["RollupSummaryDocument"];
export type WeeklyRollupDocument = PlannerSchemas["WeeklyRollupDocument"];
export type DailyRollupDocument = PlannerSchemas["DailyRollupDocument"];
export type ReoptSuggestionDto = PlannerSchemas["ReoptSuggestionDto"];
export type PlanReoptSuggestionDto = PlannerSchemas["PlanReoptSuggestionDto"];
export type ProposedReoptAssignmentsDocument =
  PlannerSchemas["ProposedReoptAssignmentsDocument"];
export type ProposedSlotChange = PlannerSchemas["ProposedSlotChange"];
export type FeasibilityCheckResultDto = PlannerSchemas["FeasibilityCheckResultDto"];
export type ConstraintConflictDto = PlannerSchemas["ConstraintConflictDto"];
export type ResolutionOptionDto = PlannerSchemas["ResolutionOptionDto"];
export type GeneratePlanRequest = PlannerSchemas["GeneratePlanRequest"];

/** Status-mark glyph set: the five contract slot states plus the derived
 *  affected-by-suggestion overlay (NOT a slot state — spec §3d). */
export type SlotMark = SlotState | "AFFECTED";

/* ---- generation flow state ----------------------------------------------------- */

export type GenerationStatus = "idle" | "generating" | "review";

/**
 * POST /plans/generate intent state, including the Idempotency-Key fake:
 * one key per user intent, persisted until a 2xx lands; re-submitting with
 * the same key serves the cached plan back (200 replay, spec §4b).
 */
export interface GenerationState {
  status: GenerationStatus;
  /** Target Monday (GeneratePlanRequest.weekStartDate). */
  weekStartDate: string;
  /** GeneratePlanRequest.forceRegenerateIfActive consent checkbox. */
  forceRegenerateIfActive: boolean;
  /** Current intent's Idempotency-Key; "Regenerate all" mints a new one. */
  idempotencyKey: string | null;
  /** Mock server replay cache: Idempotency-Key → planId already served. */
  served: Record<string, string>;
  resultPlanId: string | null;
  /** True when the last response was a 200 cached replay (vs 201 created). */
  replayed: boolean;
  /** Regeneration round — deterministically varies generated content. */
  round: number;
}

export interface PlannerState {
  /** Every generation the mock knows, across weeks (the plan store #1–#4). */
  plans: PlanDto[];
  /** PENDING re-opt suggestions (#12 list — no proposedAssignments pre-accept). */
  suggestions: ReoptSuggestionDto[];
  /**
   * Mock server side: suggestionId → diff revealed by the accept response.
   * The list DTO deliberately omits this (contract gap, spec §8 Q2).
   */
  proposedBySuggestion: Record<string, ProposedReoptAssignmentsDocument>;
  /** Accept response (#13) held for the post-accept review panel. */
  lastReoptOutcome: { dto: PlanReoptSuggestionDto; newPlanId: string } | null;
  /** weekStartDate → feasibility check result (#5). */
  feasibility: Record<string, FeasibilityCheckResultDto>;
  /**
   * Slot the mock "server" has already advanced on another device — first
   * action on it returns the 409 + re-fetch demo (spec §8 status map).
   */
  racedSlot: { slotId: string; serverState: SlotState } | null;
  generation: GenerationState;
}

/* ---- recipes -------------------------------------------------------------- */

export type QualityTier =
  | "user verified"
  | "imported"
  | "ai generated"
  | "web discovered";

export interface RecipeIngredient {
  n: string;
  q: string;
  /** Substitution chip, e.g. "swap: tamari". */
  swap?: string;
}

export interface RecipePendingChange {
  title: string;
  sub: string;
  from: string;
  to: string;
  /** Name of the ingredient the delta applies to. */
  ingredient: string;
  /** New quantity for that ingredient when accepted. */
  newQty: string;
}

export interface Recipe {
  id: string;
  name: string;
  cuisine: string;
  timeMin: number;
  serves: number;
  /** Headline taste score, 0–100. */
  taste: number;
  tier: QualityTier;
  img: string;
  source: string;
  ratings: Array<{ label: string; val: number }>;
  /** Per-serving pills, e.g. "520 kcal". */
  nutrition: string[];
  ingredients: RecipeIngredient[];
  moreIngredients?: string;
  steps: string[];
  moreSteps?: string;
  /** Newest first; index 0 carries the " current" suffix. */
  versions: string[];
  pendingChange: RecipePendingChange | null;
}

/* ---- grocery -------------------------------------------------------------- */

export type GroceryItemState = "open" | "bought";

export interface GroceryItem {
  n: string;
  q: string;
  price: string;
  state: GroceryItemState;
  stale?: boolean;
  /** Provenance chip, e.g. "added by suggested fix". */
  note?: string;
}

export interface GroceryGroup {
  name: string;
  items: GroceryItem[];
}

export interface GroceryOrder {
  provider: string;
  state: string;
  eta: string;
  steps: string[];
  /** Index into steps of the current state. */
  at: number;
}

export interface GrocerySubstitution {
  from: string;
  to: string;
  reason: string;
  delta: string;
  /** Name of the list line the accepted swap replaces. */
  targetItem: string;
  /** Replacement line values applied on accept. */
  replacement: { n: string; q: string; price: string };
}

export interface GroceryState {
  contextLine: string;
  projectedTotal: string;
  projectedConf: string;
  headroom: string;
  headroomSub: string;
  groups: GroceryGroup[];
  order: GroceryOrder | null;
  substitution: GrocerySubstitution | null;
}

/* ---- pantry --------------------------------------------------------------- */

export type PantryLocation = "fridge" | "freezer" | "pantry";

export interface PantryItem {
  id: string;
  name: string;
  location: PantryLocation;
  qty: number;
  unit: string;
  /** ISO date, e.g. "2026-06-12". */
  expiry: string;
  /** Estimated value in £ — used for waste accounting when spoiled. */
  estCost: number;
  spoiled?: boolean;
}

export interface WasteEntry {
  name: string;
  cost: string;
  when: string;
}

export interface PantryState {
  items: PantryItem[];
  equipment: string[];
  waste: { monthTotal: number; entries: WasteEntry[] };
  budget: { spent: number; total: number; note: string };
}

/* ---- notifications --------------------------------------------------------- */

export type NotificationKind =
  | "plan"
  | "recipe"
  | "grocery"
  | "order"
  | "pantry"
  | "expiry"
  | "ai";

export interface AppNotification {
  id: string;
  kind: NotificationKind;
  title: string;
  time: string;
  read: boolean;
}

/* ---- adaptation (Today's suggestion teaser) ----------------------------------
 * Contract shape for GET /adaptation/pending-changes (today.md §3f). Today
 * shows row 1; the Activity page owns the full top-3.
 */

export type PendingChangeListItemDto =
  components["schemas"]["PendingChangeListItemDto"];

export interface AdaptationState {
  /** Ranked pending recipe changes, server-ordered best-first. */
  pendingChanges: PendingChangeListItemDto[];
}

/* ---- toasts (transient UI, not a DTO) ------------------------------------------ */

export interface ToastItem {
  id: number;
  text: string;
  /** warn = 409/422-style guard messages (amber). */
  tone: "info" | "warn";
}

/* ---- nutrition: backend DTO mirrors ------------------------------------------
 * Re-exported from the generated OpenAPI types so the mock store carries the
 * exact production field names (spec: design/frontend/pages/nutrition.md §2).
 */

type Schemas = components["schemas"];

export type MealSlot = Schemas["MealSlot"];
export type ActivityLevel = Schemas["ActivityLevel"];
export type Goal = Schemas["Goal"];
export type EnforcementDirection = Schemas["EnforcementDirection"];
export type IntakeSlotStatus = Schemas["IntakeSlotStatus"];
export type IntakeSource = Schemas["IntakeSource"];

export type PlannedIntakeDto = Schemas["PlannedIntakeDto"];
export type ActualIntakeDto = Schemas["ActualIntakeDto"];
export type IntakeSlotDto = Schemas["IntakeSlotDto"];
export type IntakeSnackDto = Schemas["IntakeSnackDto"];
export type IntakeDayDto = Schemas["IntakeDayDto"];
export type IntakeEntryDto = Schemas["IntakeEntryDto"];
export type LogSnackRequest = Schemas["LogSnackRequest"];

export type DailyAggregateDto = Schemas["DailyAggregateDto"];
export type MacroAggregateDto = Schemas["MacroAggregateDto"];
export type WeeklyAggregateDto = Schemas["WeeklyAggregateDto"];

export type CalorieTargetDto = Schemas["CalorieTargetDto"];
export type MacroTargetDto = Schemas["MacroTargetDto"];
export type MicroTargetDto = Schemas["MicroTargetDto"];
export type PerMealDistributionDto = Schemas["PerMealDistributionDto"];
export type ActivityAdjustmentDto = Schemas["ActivityAdjustmentDto"];
export type TargetsDto = Schemas["TargetsDto"];
export type UpdateTargetsRequest = Schemas["UpdateTargetsRequest"];
export type DailyActivityDto = Schemas["DailyActivityDto"];

export type FoodMoodEntryDto = Schemas["FoodMoodEntryDto"];

export type HealthDirectiveDto = Schemas["HealthDirectiveDto"];
export type DirectiveStatus = Schemas["DirectiveStatus"];
export type DirectiveType = Schemas["DirectiveType"];
export type DirectiveInstructionDocument = Schemas["DirectiveInstructionDocument"];
export type DirectivePhaseDto = Schemas["DirectivePhaseDto"];
export type SafetyFindingDto = Schemas["SafetyFindingDto"];
export type SafetyGateVerdict = Schemas["SafetyGateVerdict"];
/** Shape sent on accept-with-modification (AcceptDirectiveRequest.userModification). */
export type DirectiveUserModification = NonNullable<
  Schemas["AcceptDirectiveRequest"]["userModification"]
>;

export type IngredientNutritionDto = Schemas["IngredientNutritionDto"];
export type IngredientNutritionDocument = Schemas["IngredientNutritionDocument"];
export type IngredientMappingSource = Schemas["IngredientMappingSource"];

export interface NutritionState {
  /** ISO date → intake day. The seed covers the plan week (Mon–Sun). */
  intakeDays: Record<string, IntakeDayDto>;
  /**
   * Slot ids currently inside the fake AI-parse window after an override
   * (transient UI state — not part of any DTO).
   */
  parsingSlotIds: string[];
  /** ISO date → daily activity entry (PUT targets/activity/{date}). */
  dailyActivity: Record<string, DailyActivityDto>;
  /** Food & mood journal, newest first (all dates). */
  journal: FoodMoodEntryDto[];
  /** Health directives inbox, newest first. */
  directives: HealthDirectiveDto[];
  /** Ingredient nutrition cache rows (lookup assist + data-quality queue). */
  ingredientCache: IngredientNutritionDto[];
}

/* ---- preferences ------------------------------------------------------------- */

export interface TasteGroup {
  name: string;
  likes: string[];
  dislikes: string[];
}

export type ConstraintKind = "allergy" | "dietary";

export interface LifestyleConfig {
  slotTimes: Record<MealSlotKey, string>;
  /** Portion multiplier, e.g. 1.0. */
  portionScale: number;
  /** Weekly grocery budget in £ — mirrored to pantry budget + grocery headroom. */
  weeklyBudget: number;
}

export interface PreferencesState {
  profileVersion: number;
  refreshing: boolean;
  groups: TasteGroup[];
  /** Hard constraints — removal requires the GAP-04 interstitial. */
  allergies: string[];
  dietary: string[];
  lifestyle: LifestyleConfig;
}

/* ---- activity / feedback -------------------------------------------------------- */

/** ✓ olive ≥0.8 routed · ? amber 0.5–0.8 check me · … terra <0.5 needs you. */
export type ConfidenceTier = "high" | "mid" | "low";

export interface FeedbackRoute {
  dest: string;
  conf: number;
  /** Routed/check-me description of what the destination will do. */
  action?: string;
  /** Low-confidence clarification question (advisor voice). */
  question?: string;
  options?: string[];
  /** The chosen clarification option once answered. */
  answered?: string;
}

export interface FeedbackEntry {
  id: string;
  when: string;
  /** The user's words, shown plain and quoted — never serif. */
  text: string;
  routes: FeedbackRoute[];
  /** "This isn't right" pressed — correction recorded. */
  corrected?: boolean;
}

export interface Clarification {
  id: string;
  question: string;
  options: string[];
  /** The feedback text that raised the question. */
  context?: string;
}

export interface ActivityState {
  feedback: FeedbackEntry[];
  clarifications: Clarification[];
}

/* ---- notification preferences ----------------------------------------------------- */

export interface NotificationPrefs {
  /** Muted kinds drop out of the bell dropdown + rail badge. */
  muted: NotificationKind[];
  quietStart: string;
  quietEnd: string;
}

/* ---- household / settings ----------------------------------------------------------- */

export type MemberRole = "owner" | "adult" | "child";

export interface HouseholdMember {
  id: string;
  name: string;
  role: MemberRole;
  /** Per-member identity dot (CSS colour value). */
  color: string;
}

export interface PendingInvite {
  email: string;
  sent: string;
}

export interface SlotConfigEntry {
  slot: MealSlotKey;
  time: string;
  /** Shared household meal vs per-person. */
  shared: boolean;
}

export interface DayTypeSlots {
  dayType: string;
  slots: SlotConfigEntry[];
}

export interface HouseholdState {
  name: string;
  members: HouseholdMember[];
  invites: PendingInvite[];
  slotConfig: DayTypeSlots[];
  /** Account email (display only). */
  email: string;
}

/* ---- discovery ------------------------------------------------------------------------ */

export type DiscoveryStep = "QUEUED" | "SEARCHING" | "FILTERING" | "DONE";

export interface DiscoveryResult {
  id: string;
  title: string;
  domain: string;
  /** AI-filter confidence, 0–1. */
  conf: number;
  status: "new" | "kept" | "skipped";
  timeMin: number;
  cuisine: string;
}

export interface DiscoverySource {
  domain: string;
  hits: number;
}

export interface DiscoveryJob {
  id: string;
  query: string;
  constraints: string[];
  step: DiscoveryStep;
  /** Populated when the job reaches DONE. */
  results: DiscoveryResult[];
  sources: DiscoverySource[];
}

export interface DiscoveryHistoryEntry {
  query: string;
  when: string;
  found: number;
  kept: number;
}

export interface DiscoveryState {
  job: DiscoveryJob | null;
  history: DiscoveryHistoryEntry[];
}

/* ---- root ------------------------------------------------------------------ */

export interface StoreState {
  planner: PlannerState;
  adaptation: AdaptationState;
  recipes: Recipe[];
  grocery: GroceryState;
  pantry: PantryState;
  notifications: AppNotification[];
  nutrition: NutritionState;
  targets: TargetsDto;
  preferences: PreferencesState;
  activity: ActivityState;
  notificationPrefs: NotificationPrefs;
  household: HouseholdState;
  discovery: DiscoveryState;
  /** Transient toast stack (409-guard + confirmation messages). */
  toasts: ToastItem[];
}
