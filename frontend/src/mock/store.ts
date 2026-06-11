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
  BASE_CANDIDATES,
  createSeed,
  DISCOVERY_IMGS,
  DISCOVERY_RESULTS,
  DISCOVERY_SOURCES,
  MOCK_TODAY_ISO,
} from "./seed";
import type {
  ActivityLevel,
  AppNotification,
  ConfidenceTier,
  ConstraintKind,
  DailyAggregateDto,
  DirectiveUserModification,
  DiscoveryResult,
  DiscoveryStep,
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
  MealSlotKey,
  NotificationKind,
  NutritionState,
  PlanCandidate,
  PlanDay,
  PlanStat,
  PlanState,
  Recipe,
  ReoptFix,
  SlotState,
  StoreState,
  TargetsDto,
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

/* ---- plan: slot states ------------------------------------------------------- */

const SLOT_ORDER: Record<SlotState, number> = {
  planned: 0,
  affected: 0,
  cooking: 1,
  cooked: 2,
  eaten: 3,
};

function updateDay(
  days: PlanDay[],
  day: string,
  fn: (d: PlanDay) => PlanDay,
): PlanDay[] {
  return days.map((d) => (d.day === day ? fn(d) : d));
}

function updateSlot(
  days: PlanDay[],
  day: string,
  slot: MealSlotKey,
  fn: (s: PlanDay["slots"][MealSlotKey]) => PlanDay["slots"][MealSlotKey],
): PlanDay[] {
  return updateDay(days, day, (d) => ({
    ...d,
    slots: { ...d.slots, [slot]: fn(d.slots[slot]) },
  }));
}

/**
 * Advance a slot through its lifecycle. Eaten slots are pinned — they never
 * move backwards — and no transition may go backwards in the lifecycle.
 */
export function setSlotState(
  day: string,
  slot: MealSlotKey,
  next: SlotState,
): void {
  mutate((s) => {
    const d = s.plan.days.find((x) => x.day === day);
    if (!d) return s;
    const current = d.slots[slot].state;
    if (current === "eaten") return s; // pinned
    if (SLOT_ORDER[next] < SLOT_ORDER[current]) return s; // never backwards

    let out: StoreState = {
      ...s,
      plan: {
        ...s.plan,
        days: updateSlot(s.plan.days, day, slot, (sl) => ({
          ...sl,
          state: next,
        })),
      },
    };

    // Marking one of today's slots eaten confirms its intake (idempotent —
    // no double-credit when already confirmed from the Nutrition page).
    if (next === "eaten" && d.today) {
      out = confirmSlotIn(out, MOCK_TODAY_ISO, MEAL_SLOT_BY_KEY[slot]);
    }
    return out;
  });
}

/* ---- plan: re-optimisation fix -------------------------------------------------- */

function applyFixSwaps(plan: PlanState, fix: ReoptFix): PlanState {
  let days = plan.days;
  for (const sw of fix.swaps) {
    days = updateSlot(days, sw.day, sw.slot, () => ({
      name: sw.to,
      state: "planned",
    }));
  }
  // Any stray affected marks not covered by the swaps revert to planned.
  days = clearAffected(days);
  return {
    ...plan,
    days,
    stats: fix.statsAfter ?? plan.stats,
    fix: null,
  };
}

function clearAffected(days: PlanDay[]): PlanDay[] {
  return days.map((d) => {
    const entries = Object.entries(d.slots) as Array<
      [MealSlotKey, PlanDay["slots"][MealSlotKey]]
    >;
    if (entries.every(([, sl]) => sl.state !== "affected")) return d;
    const slots = { ...d.slots };
    for (const [key, sl] of entries) {
      if (sl.state === "affected") slots[key] = { ...sl, state: "planned" };
    }
    return { ...d, slots };
  });
}

export function acceptReoptFix(): void {
  mutate((s) => {
    if (!s.plan.fix) return s;
    const swapped = s.plan.fix.swaps
      .map((sw) => `${sw.slotLabel} → ${sw.to.toLowerCase()}`)
      .join(", ");
    const out = { ...s, plan: applyFixSwaps(s.plan, s.plan.fix) };
    return pushNotification(out, "plan", `Plan updated — ${swapped}`);
  });
}

export function dismissReoptFix(): void {
  mutate((s) => {
    if (!s.plan.fix) return s;
    return {
      ...s,
      plan: { ...s.plan, fix: null, days: clearAffected(s.plan.days) },
    };
  });
}

/* ---- plan: generation flow ------------------------------------------------------- */

/**
 * Deterministic per-round score variation (no backend, no real randomness
 * needed): each regeneration rotates a small offset over fit and variety.
 */
function buildCandidates(round: number): PlanCandidate[] {
  const vary = (base: number, id: number, spread: number): number => {
    if (round === 0) return base;
    const offset = ((round * 7 + id * 3) % (spread * 2 + 1)) - spread;
    return Math.max(50, Math.min(99, base + offset));
  };
  const built = BASE_CANDIDATES.map((c) => ({
    id: c.id,
    fit: vary(c.baseFit, c.id, 3),
    nutrition: c.nutrition,
    cost: c.cost,
    conf: c.conf,
    variety: `${vary(parseInt(c.variety, 10), c.id + 2, 2)}%`,
    prep: c.prep,
    warn: c.warn,
    reasoning: c.reasoning,
    preview: c.preview,
  }));
  const top = built.reduce((a, b) => (b.fit > a.fit ? b : a));
  return built.map((c) =>
    c.id === top.id ? { ...c, recommended: true } : c,
  );
}

function startGeneration(s: StoreState, round: number): StoreState {
  return {
    ...s,
    generation: { ...s.generation, status: "generating", round },
  };
}

function finishGeneration(): void {
  mutate((s) => {
    if (s.generation.status !== "generating") return s;
    return {
      ...s,
      generation: {
        ...s.generation,
        status: "ready",
        candidates: buildCandidates(s.generation.round),
      },
    };
  });
}

/** Async fake: ~1.5s in "generating", then five seeded candidates. */
export function generatePlan(): void {
  if (state.generation.status === "generating") return;
  mutate((s) => startGeneration(s, s.generation.round));
  setTimeout(finishGeneration, 1500);
}

/** Re-roll all five candidates with deterministically varied scores. */
export function regenerate(): void {
  if (state.generation.status === "generating") return;
  mutate((s) => startGeneration(s, s.generation.round + 1));
  setTimeout(finishGeneration, 1500);
}

function statsForCandidate(c: PlanCandidate): PlanStat[] {
  const warnCount = c.warn?.includes("quality")
    ? (c.warn.match(/\d+/)?.[0] ?? "1")
    : "0";
  return [
    { label: "Variety", value: c.variety },
    { label: "Est. cost", value: c.cost, sub: c.conf },
    {
      label: "Protein on target",
      value: c.nutrition.startsWith("on target") ? "7 of 7 days" : "5 of 7 days",
    },
    { label: "Quality warnings", value: warnCount, warn: warnCount !== "0" },
  ];
}

/**
 * Apply a candidate's dinner line-up to the active plan. Pinned slots
 * (eaten / cooking / cooked) are immutable and keep their meal.
 */
export function acceptCandidate(id: number): void {
  mutate((s) => {
    const candidate = s.generation.candidates.find((c) => c.id === id);
    if (!candidate) return s;
    const days = s.plan.days.map((d, i) => {
      const dinner = d.slots.dinner;
      const replacement = candidate.preview[i];
      if (
        replacement === undefined ||
        dinner.state === "eaten" ||
        dinner.state === "cooked" ||
        dinner.state === "cooking"
      ) {
        return d;
      }
      return {
        ...d,
        slots: {
          ...d.slots,
          dinner: {
            name: replacement,
            state: "planned" as const,
            batch: replacement.startsWith("Batch:") || undefined,
          },
        },
      };
    });
    const out: StoreState = {
      ...s,
      plan: {
        ...s.plan,
        days: clearAffected(days),
        stats: statsForCandidate(candidate),
        meta: `regenerated Wednesday · accepted from ${s.generation.candidates.length} candidates`,
        fix: null,
      },
      generation: { ...s.generation, status: "idle", candidates: [] },
    };
    return pushNotification(
      out,
      "plan",
      `New dinner line-up accepted — candidate ${id} applied to unpinned slots`,
    );
  });
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

/* ---- recipes --------------------------------------------------------------------------- */

function bumpVersions(versions: string[]): string[] {
  const head = versions[0] ?? "v0 current";
  const n = parseInt(head.replace(/^v/, ""), 10);
  return [
    `v${(Number.isNaN(n) ? 0 : n) + 1} current`,
    ...versions.map((v) => v.replace(" current", "")),
  ];
}

/** Clear the Today suggestion when it points at this recipe's change. */
function clearLinkedSuggestion(s: StoreState, recipeId: string): StoreState {
  if (s.today.suggestion?.recipeId !== recipeId) return s;
  return {
    ...s,
    today: {
      ...s.today,
      suggestion: null,
      attention: s.today.attention.filter((a) => a.kind !== "ai"),
    },
  };
}

function applyRecipeChange(s: StoreState, recipeId: string): StoreState {
  const recipe = s.recipes.find((r) => r.id === recipeId);
  const change = recipe?.pendingChange;
  if (!recipe || !change) return s;
  const recipes = s.recipes.map((r) =>
    r.id !== recipeId
      ? r
      : {
          ...r,
          versions: bumpVersions(r.versions),
          ingredients: r.ingredients.map((it) =>
            it.n === change.ingredient ? { ...it, q: change.newQty } : it,
          ),
          pendingChange: null,
        },
  );
  const newVersion = bumpVersions(recipe.versions)[0].replace(" current", "");
  return pushNotification(
    clearLinkedSuggestion({ ...s, recipes }, recipeId),
    "recipe",
    `${recipe.name} updated — ${change.title.toLowerCase()} (${newVersion} created)`,
  );
}

export function acceptRecipeChange(recipeId: string): void {
  mutate((s) => applyRecipeChange(s, recipeId));
}

export function rejectRecipeChange(recipeId: string): void {
  mutate((s) => {
    const recipe = s.recipes.find((r) => r.id === recipeId);
    if (!recipe?.pendingChange) return s;
    return clearLinkedSuggestion(
      {
        ...s,
        recipes: s.recipes.map((r) =>
          r.id === recipeId ? { ...r, pendingChange: null } : r,
        ),
      },
      recipeId,
    );
  });
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
 * Mark a pantry item spoiled: logs waste, surfaces an attention item on
 * Today, and — when no fix is already pending — raises a re-optimisation
 * fix card on Plan (cross-page liveliness).
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
      today: {
        ...s.today,
        attention: [
          {
            kind: "expiry" as const,
            text: `${item.name} marked spoiled — check the plan fix before Thursday`,
          },
          ...s.today.attention,
        ],
      },
    };

    if (!out.plan.fix) {
      // Target the first future dinner still in "planned" state.
      const target = out.plan.days.find(
        (d) =>
          ["Thu", "Fri", "Sat", "Sun"].includes(d.day) &&
          d.slots.dinner.state === "planned",
      );
      if (target) {
        const fix: ReoptFix = {
          title: `${item.name} marked spoiled`,
          sub: "1 future slot affected · eaten and cooked meals stay pinned",
          swaps: [
            {
              day: target.day,
              slot: "dinner",
              slotLabel: `${target.day} dinner`,
              from: target.slots.dinner.name,
              to: "One-pot tomato orzo",
              note: "pantry-friendly",
            },
          ],
          impact: "Cost −£0.60 · protein unchanged · variety +1%",
        };
        out = {
          ...out,
          plan: {
            ...out.plan,
            fix,
            days: updateSlot(out.plan.days, target.day, "dinner", (sl) => ({
              ...sl,
              state: "affected",
            })),
          },
        };
        out = pushNotification(
          out,
          "pantry",
          `${item.name} marked spoiled — fix suggested for ${target.day} dinner`,
        );
        return out;
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

const MEAL_SLOT_BY_KEY: Record<MealSlotKey, MealSlot> = {
  breakfast: "BREAKFAST",
  lunch: "LUNCH",
  dinner: "DINNER",
};

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

/* ---- today --------------------------------------------------------------------------------- */

/** Accept the Today suggestion: applies the linked recipe's pending change. */
export function acceptTodaySuggestion(): void {
  mutate((s) => {
    const suggestion = s.today.suggestion;
    if (!suggestion) return s;
    const out = applyRecipeChange(s, suggestion.recipeId);
    return {
      ...out,
      today: {
        ...out.today,
        suggestion: null,
        attention: out.today.attention.filter((a) => a.kind !== "ai"),
      },
    };
  });
}

export function dismissTodaySuggestion(): void {
  mutate((s) => {
    if (!s.today.suggestion) return s;
    return {
      ...s,
      today: {
        ...s.today,
        suggestion: null,
        attention: s.today.attention.filter((a) => a.kind !== "ai"),
      },
    };
  });
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

/** Nudge a lifestyle slot time ±15 min; Today's timeline mirrors it. */
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
      today: {
        ...s.today,
        slotMeta: {
          ...s.today.slotMeta,
          [slot]: { ...s.today.slotMeta[slot], time },
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

/* ---- discovery -------------------------------------------------------------------------------------------- */

let discoverySeq = 10;

const DISCOVERY_STEPS: DiscoveryStep[] = [
  "QUEUED",
  "SEARCHING",
  "FILTERING",
  "DONE",
];

function advanceDiscovery(jobId: string): void {
  mutate((s) => {
    const job = s.discovery.job;
    if (!job || job.id !== jobId || job.step === "DONE") return s;
    const next =
      DISCOVERY_STEPS[DISCOVERY_STEPS.indexOf(job.step) + 1] ?? "DONE";
    if (next !== "DONE") {
      return { ...s, discovery: { ...s.discovery, job: { ...job, step: next } } };
    }
    const results: DiscoveryResult[] = DISCOVERY_RESULTS.map((r) => ({
      ...r,
      status: "new",
    }));
    return pushNotification(
      {
        ...s,
        discovery: {
          ...s.discovery,
          job: {
            ...job,
            step: "DONE",
            results,
            sources: [...DISCOVERY_SOURCES],
          },
        },
      },
      "ai",
      `Discovery finished — ${results.length} candidates from ${DISCOVERY_SOURCES.length} sources`,
    );
  });
  if (state.discovery.job?.id === jobId && state.discovery.job.step !== "DONE") {
    setTimeout(() => advanceDiscovery(jobId), 1000);
  }
}

/**
 * Start a fake discovery job: QUEUED → SEARCHING → FILTERING → DONE on a
 * ~1s timer per step. A finished previous job is archived to history.
 */
export function startDiscovery(query: string, constraints: string[]): void {
  if (state.discovery.job && state.discovery.job.step !== "DONE") return;
  const id = `job${++discoverySeq}`;
  mutate((s) => {
    const prev = s.discovery.job;
    const history =
      prev && prev.step === "DONE"
        ? [
            {
              query: prev.query,
              when: "Today",
              found: prev.results.length,
              kept: prev.results.filter((r) => r.status === "kept").length,
            },
            ...s.discovery.history,
          ]
        : s.discovery.history;
    return {
      ...s,
      discovery: {
        ...s.discovery,
        history,
        job: {
          id,
          query: query.trim() || "weeknight dinners",
          constraints,
          step: "QUEUED",
          results: [],
          sources: [],
        },
      },
    };
  });
  setTimeout(() => advanceDiscovery(id), 1000);
}

/** Build a catalogue entry for a kept discovery result. */
function makeDiscoveredRecipe(r: DiscoveryResult, imgIdx: number): Recipe {
  const taste = Math.round(68 + r.conf * 20);
  return {
    id: `disc-${r.id}-${discoverySeq}`,
    name: r.title,
    cuisine: r.cuisine,
    timeMin: r.timeMin,
    serves: 4,
    taste,
    tier: "web discovered",
    img: DISCOVERY_IMGS[imgIdx % DISCOVERY_IMGS.length],
    source: `Discovered from ${r.domain} · version 1`,
    ratings: [
      { label: "Taste", val: taste },
      { label: "Worth the effort", val: taste - 4 },
      { label: "Portion fit", val: taste - 7 },
      { label: "Would repeat", val: taste - 5 },
    ],
    nutrition: ["≈480 kcal", "≈24 g protein", "≈52 g carbs", "≈16 g fat"],
    ingredients: [
      { n: "Olive oil", q: "2 tbsp" },
      { n: "Garlic", q: "2 cloves" },
      { n: "Seasonal vegetables", q: "400 g" },
    ],
    moreIngredients: `+ full list from ${r.domain}`,
    steps: [
      "Outline imported from the source page on keep.",
      "Cook to the source method — timings verified on import.",
    ],
    moreSteps: "+ full method from source",
    versions: ["v1 current"],
    pendingChange: null,
  };
}

/** Keep a discovery result: adds it to the recipe catalogue. */
export function keepDiscoveryResult(id: string): void {
  mutate((s) => {
    const job = s.discovery.job;
    const result = job?.results.find((r) => r.id === id);
    if (!job || !result || result.status !== "new") return s;
    const imgIdx = job.results.indexOf(result);
    return pushNotification(
      {
        ...s,
        recipes: [...s.recipes, makeDiscoveredRecipe(result, imgIdx)],
        discovery: {
          ...s.discovery,
          job: {
            ...job,
            results: job.results.map((r) =>
              r.id === id ? { ...r, status: "kept" } : r,
            ),
          },
        },
      },
      "recipe",
      `${result.title} added to your catalogue — web discovered`,
    );
  });
}

export function skipDiscoveryResult(id: string): void {
  mutate((s) => {
    const job = s.discovery.job;
    if (!job) return s;
    return {
      ...s,
      discovery: {
        ...s.discovery,
        job: {
          ...job,
          results: job.results.map((r) =>
            r.id === id && r.status === "new" ? { ...r, status: "skipped" } : r,
          ),
        },
      },
    };
  });
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
