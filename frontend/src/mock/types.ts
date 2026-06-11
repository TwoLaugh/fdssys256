/**
 * Types for the in-memory mock store that makes the app fully playable
 * without a backend. Shapes are ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and expanded
 * where pages need more.
 */

/* ---- plan ---------------------------------------------------------------- */

export type MealSlotKey = "breakfast" | "lunch" | "dinner";

/**
 * Slot lifecycle: planned → cooking → cooked → eaten (pinned, never
 * backwards). "affected" = struck through by a pending re-optimisation fix.
 */
export type SlotState = "planned" | "cooking" | "cooked" | "eaten" | "affected";

export interface PlanSlot {
  name: string;
  state: SlotState;
  /** Linked to a batch-cook. */
  batch?: boolean;
}

export interface PlanDay {
  /** Short day name, e.g. "Mon". */
  day: string;
  /** Day of month, e.g. 8. */
  date: number;
  today?: boolean;
  slots: Record<MealSlotKey, PlanSlot>;
}

export interface PlanStat {
  label: string;
  value: string;
  sub?: string;
  warn?: boolean;
}

export interface ReoptSwap {
  day: string;
  slot: MealSlotKey;
  /** Display label, e.g. "Thu dinner". */
  slotLabel: string;
  from: string;
  to: string;
  note?: string;
}

export interface ReoptFix {
  title: string;
  sub: string;
  swaps: ReoptSwap[];
  impact: string;
  /** Stat band values to apply when the fix is accepted (seeded fix only). */
  statsAfter?: PlanStat[];
}

export interface PlanState {
  title: string;
  range: string;
  meta: string;
  stats: PlanStat[];
  days: PlanDay[];
  /** Pending re-optimisation fix, if any. */
  fix: ReoptFix | null;
}

/* ---- generation ----------------------------------------------------------- */

export interface PlanCandidate {
  id: number;
  fit: number;
  recommended?: boolean;
  nutrition: string;
  cost: string;
  conf: string;
  variety: string;
  prep: string;
  warn: string | null;
  /** Advisor-voice "why this candidate" line. */
  reasoning: string;
  /** Seven dinner line-up chips, Mon → Sun. */
  preview: string[];
}

export type GenerationStatus = "idle" | "generating" | "ready";

export interface GenerationState {
  status: GenerationStatus;
  /** Regeneration round — used to deterministically vary scores. */
  round: number;
  title: string;
  context: string;
  feasibility: string;
  candidates: PlanCandidate[];
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

/* ---- today ----------------------------------------------------------------- */

export type AttentionKind = "expiry" | "defrost" | "ai";

export interface AttentionItem {
  kind: AttentionKind;
  text: string;
}

export interface TodaySlotMeta {
  time: string;
  meta: string;
  /** Calories credited when the slot is marked eaten. */
  kcal: number;
  alert?: string;
}

export interface NutritionEntry {
  label: string;
  value: number;
  target: number;
  /** Unit suffix for the target display, e.g. " g". Empty for kcal. */
  unit: string;
  behind?: boolean;
}

export interface TodaySuggestion {
  label: string;
  title: string;
  sub: string;
  /** Recipe whose pending change this suggestion applies on accept. */
  recipeId: string;
}

export interface TodayState {
  dateLabel: string;
  progressLabel: string;
  greeting: string;
  slotMeta: Record<MealSlotKey, TodaySlotMeta>;
  attention: AttentionItem[];
  suggestion: TodaySuggestion | null;
  nutrition: NutritionEntry[];
}

/* ---- nutrition -------------------------------------------------------------- */

export type IntakeStatus = "pending" | "confirmed" | "skipped";

/** Per-slot intake record for today: planned kcal vs what was actually eaten. */
export interface IntakeSlot {
  slot: MealSlotKey;
  plannedKcal: number;
  /** Set when confirmed; null while pending or skipped. */
  actualKcal: number | null;
  status: IntakeStatus;
}

export interface SnackEntry {
  name: string;
  kcal: number;
}

/** Mutable macro targets — canonical; today's stat bars mirror these. */
export interface NutritionTargets {
  calories: number;
  protein: number;
  carbs: number;
  fat: number;
}

export type MacroKey = keyof NutritionTargets;

export interface JournalEntry {
  when: string;
  text: string;
  /** Free-form mood note, e.g. "energised". */
  mood?: string;
}

export interface WeekDayIntake {
  day: string;
  /** kcal logged that day; 0 = nothing logged yet. */
  kcal: number;
  today?: boolean;
}

export interface NutritionState {
  intake: IntakeSlot[];
  snacks: SnackEntry[];
  /** Mon → Sun; today's kcal is read live from the calories entry. */
  week: WeekDayIntake[];
  journal: JournalEntry[];
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
  plan: PlanState;
  generation: GenerationState;
  recipes: Recipe[];
  grocery: GroceryState;
  pantry: PantryState;
  notifications: AppNotification[];
  today: TodayState;
  nutrition: NutritionState;
  targets: NutritionTargets;
  preferences: PreferencesState;
  activity: ActivityState;
  notificationPrefs: NotificationPrefs;
  household: HouseholdState;
  discovery: DiscoveryState;
}
