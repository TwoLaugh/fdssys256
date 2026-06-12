/**
 * Seed data for the mock store — ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and expanded
 * to cover every page: 12 recipes, full week plan, grocery groups, pantry
 * inventory with expiry dates, notifications.
 */

import { createGrocerySeed, createPantrySeed } from "./groceryPantrySeed";
import { createNutritionSeed, targetsSeed } from "./nutritionSeed";
import { createPlannerSeed } from "./plannerSeed";
import { createActivitySeed, createPreferencesSeed } from "./prefActivitySeed";
import {
  createAdaptationSeed,
  createDiscoverySeed,
  createRecipeSeed,
} from "./recipeSeed";
import type {
  HouseholdState,
  NotificationPrefs,
  StoreState,
} from "./types";

/** The mock's fixed "today" (Wednesday 10 June 2026) — keeps expiry colour
 *  coding and date labels deterministic. Defined with the nutrition seed,
 *  whose intake days are keyed on the same week. */
export { MOCK_TODAY_ISO } from "./nutritionSeed";

/* ---- notification prefs --------------------------------------------------------------------- */

const notificationPrefsSeed: NotificationPrefs = {
  muted: [],
  quietStart: "21:00",
  quietEnd: "07:00",
};

/* ---- household ----------------------------------------------------------------------------- */

const householdSeed: HouseholdState = {
  name: "Veer household",
  members: [
    { id: "m1", name: "Iren", role: "owner", color: "var(--mp-terra)" },
    { id: "m2", name: "Sam", role: "adult", color: "var(--mp-olive)" },
    { id: "m3", name: "Maya", role: "child", color: "var(--mp-amber)" },
    { id: "m4", name: "Theo", role: "child", color: "var(--mp-mark-planned)" },
  ],
  invites: [{ email: "grandma.veer@example.com", sent: "Sent Mon 8 June" }],
  slotConfig: [
    {
      dayType: "School days",
      slots: [
        { slot: "breakfast", time: "07:30", shared: false },
        { slot: "lunch", time: "12:30", shared: false },
        { slot: "dinner", time: "18:30", shared: true },
      ],
    },
    {
      dayType: "Weekend",
      slots: [
        { slot: "breakfast", time: "09:00", shared: true },
        { slot: "lunch", time: "13:00", shared: true },
        { slot: "dinner", time: "19:00", shared: true },
      ],
    },
  ],
  email: "irenveer@gmail.com",
};

/* ---- root ------------------------------------------------------------------------- */

export function createSeed(): StoreState {
  return {
    planner: createPlannerSeed(),
    adaptation: createAdaptationSeed(),
    ...createRecipeSeed(),
    grocery: createGrocerySeed(),
    pantry: createPantrySeed(),
    notifications: [
      {
        id: "n1",
        kind: "ai",
        title: "Recipe suggestion waiting — reduce soy sauce in chicken stir-fry",
        time: "Today 07:40",
        read: false,
      },
      {
        id: "n2",
        kind: "expiry",
        title: "Spinach expires tomorrow — used in Thursday's curry",
        time: "Today 06:00",
        read: false,
      },
      {
        id: "n3",
        kind: "order",
        title: "Tesco order confirmed for Sat 13 June, 10–11am",
        time: "Yesterday 18:12",
        read: false,
      },
      {
        id: "n4",
        kind: "plan",
        title: "Generation 3 accepted — this week's plan re-optimised Tuesday",
        time: "Tue 9 June",
        read: true,
      },
    ],
    nutrition: createNutritionSeed(),
    targets: targetsSeed,
    preferences: createPreferencesSeed(),
    activity: createActivitySeed(),
    notificationPrefs: notificationPrefsSeed,
    household: householdSeed,
    discovery: createDiscoverySeed(),
    toasts: [],
  };
}
