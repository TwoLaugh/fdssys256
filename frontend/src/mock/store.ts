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
import { BASE_CANDIDATES, createSeed } from "./seed";
import type {
  AppNotification,
  MealSlotKey,
  NotificationKind,
  PlanCandidate,
  PlanDay,
  PlanStat,
  PlanState,
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

    // Marking one of today's slots eaten credits its calories.
    if (next === "eaten" && d.today) {
      const kcal = s.today.slotMeta[slot].kcal;
      out = {
        ...out,
        today: {
          ...out.today,
          nutrition: out.today.nutrition.map((n) =>
            n.label === "Calories" ? { ...n, value: n.value + kcal } : n,
          ),
        },
      };
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
    { ...s, recipes },
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
    return {
      ...s,
      recipes: s.recipes.map((r) =>
        r.id === recipeId ? { ...r, pendingChange: null } : r,
      ),
    };
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

/* ---- today --------------------------------------------------------------------------------- */

export function logSnack(name: string, kcal: number): void {
  mutate((s) =>
    pushNotification(
      {
        ...s,
        today: {
          ...s.today,
          nutrition: s.today.nutrition.map((n) =>
            n.label === "Calories" ? { ...n, value: n.value + kcal } : n,
          ),
        },
      },
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

/* ---- shared selectors --------------------------------------------------------------------------- */

export function selectUnreadCount(s: StoreState): number {
  return s.notifications.reduce((acc, n) => acc + (n.read ? 0 : 1), 0);
}
