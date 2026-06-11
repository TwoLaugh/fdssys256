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

/* ---- root ------------------------------------------------------------------ */

export interface StoreState {
  plan: PlanState;
  generation: GenerationState;
  recipes: Recipe[];
  grocery: GroceryState;
  pantry: PantryState;
  notifications: AppNotification[];
  today: TodayState;
}
