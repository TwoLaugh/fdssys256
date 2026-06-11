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
import {
  BASE_CANDIDATES,
  createSeed,
  DISCOVERY_IMGS,
  DISCOVERY_RESULTS,
  DISCOVERY_SOURCES,
} from "./seed";
import type {
  AppNotification,
  ConfidenceTier,
  ConstraintKind,
  DiscoveryResult,
  DiscoveryStep,
  FeedbackEntry,
  FeedbackRoute,
  MacroKey,
  MealSlotKey,
  NotificationKind,
  NutritionEntry,
  PlanCandidate,
  PlanDay,
  PlanStat,
  PlanState,
  Recipe,
  ReoptFix,
  SlotState,
  StoreState,
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
      out = confirmIntakeIn(out, slot);
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

/* ---- nutrition ------------------------------------------------------------------------------ */

/** Behind threshold: a macro is "behind" when under 55% of its target. */
function recomputeBehind(entries: NutritionEntry[]): NutritionEntry[] {
  return entries.map((n) => ({
    ...n,
    behind: n.target > 0 && n.value / n.target < 0.55 ? true : undefined,
  }));
}

/** Add kcal to today's calories entry and refresh the behind flags. */
function creditCalories(s: StoreState, kcal: number): StoreState {
  return {
    ...s,
    today: {
      ...s.today,
      nutrition: recomputeBehind(
        s.today.nutrition.map((n) =>
          n.label === "Calories" ? { ...n, value: n.value + kcal } : n,
        ),
      ),
    },
  };
}

/**
 * Confirm a slot's intake (idempotent — only a pending slot credits).
 * `actualKcal` overrides the planned figure when the user edits.
 */
function confirmIntakeIn(
  s: StoreState,
  slot: MealSlotKey,
  actualKcal?: number,
): StoreState {
  const entry = s.nutrition.intake.find((it) => it.slot === slot);
  if (!entry || entry.status !== "pending") return s;
  const kcal = actualKcal ?? entry.plannedKcal;
  const out: StoreState = {
    ...s,
    nutrition: {
      ...s.nutrition,
      intake: s.nutrition.intake.map((it) =>
        it.slot === slot
          ? { ...it, status: "confirmed", actualKcal: kcal }
          : it,
      ),
    },
  };
  return creditCalories(out, kcal);
}

export function confirmIntake(slot: MealSlotKey, actualKcal?: number): void {
  mutate((s) => confirmIntakeIn(s, slot, actualKcal));
}

export function skipIntake(slot: MealSlotKey): void {
  mutate((s) => {
    const entry = s.nutrition.intake.find((it) => it.slot === slot);
    if (!entry || entry.status !== "pending") return s;
    return {
      ...s,
      nutrition: {
        ...s.nutrition,
        intake: s.nutrition.intake.map((it) =>
          it.slot === slot ? { ...it, status: "skipped" } : it,
        ),
      },
    };
  });
}

/** Stepper bounds + step per macro target. */
const TARGET_RULES: Record<MacroKey, { step: number; min: number; max: number }> =
  {
    calories: { step: 50, min: 1200, max: 4000 },
    protein: { step: 5, min: 40, max: 250 },
    carbs: { step: 10, min: 80, max: 400 },
    fat: { step: 5, min: 30, max: 150 },
  };

const TARGET_LABEL: Record<MacroKey, string> = {
  calories: "Calories",
  protein: "Protein",
  carbs: "Carbs",
  fat: "Fat",
};

/** Nudge a macro target one step; bars everywhere read the same entries. */
export function adjustTarget(key: MacroKey, direction: 1 | -1): void {
  mutate((s) => {
    const rule = TARGET_RULES[key];
    const next = Math.max(
      rule.min,
      Math.min(rule.max, s.targets[key] + direction * rule.step),
    );
    if (next === s.targets[key]) return s;
    return {
      ...s,
      targets: { ...s.targets, [key]: next },
      today: {
        ...s.today,
        nutrition: recomputeBehind(
          s.today.nutrition.map((n) =>
            n.label === TARGET_LABEL[key] ? { ...n, target: next } : n,
          ),
        ),
      },
    };
  });
}

export function addJournalEntry(text: string): void {
  const trimmed = text.trim();
  if (!trimmed) return;
  mutate((s) => ({
    ...s,
    nutrition: {
      ...s.nutrition,
      journal: [{ when: "Today", text: trimmed }, ...s.nutrition.journal],
    },
  }));
}

/* ---- today --------------------------------------------------------------------------------- */

export function logSnack(name: string, kcal: number): void {
  mutate((s) =>
    pushNotification(
      creditCalories(
        {
          ...s,
          nutrition: {
            ...s.nutrition,
            snacks: [...s.nutrition.snacks, { name, kcal }],
          },
        },
        kcal,
      ),
      "ai",
      `Snack logged — ${name.toLowerCase()}, ${kcal} kcal`,
    ),
  );
}

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
