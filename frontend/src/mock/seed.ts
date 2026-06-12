/**
 * Seed data for the mock store — ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and expanded
 * to cover every page: 12 recipes, full week plan, grocery groups, pantry
 * inventory with expiry dates, notifications.
 */

import { createGrocerySeed, createPantrySeed } from "./groceryPantrySeed";
import { createNutritionSeed, targetsSeed } from "./nutritionSeed";
import { createPlannerSeed } from "./plannerSeed";
import {
  createAdaptationSeed,
  createDiscoverySeed,
  createRecipeSeed,
} from "./recipeSeed";
import type {
  ActivityState,
  HouseholdState,
  NotificationPrefs,
  PreferencesState,
  StoreState,
} from "./types";

/** The mock's fixed "today" (Wednesday 10 June 2026) — keeps expiry colour
 *  coding and date labels deterministic. Defined with the nutrition seed,
 *  whose intake days are keyed on the same week. */
export { MOCK_TODAY_ISO } from "./nutritionSeed";

/* ---- preferences --------------------------------------------------------------------- */

const preferencesSeed: PreferencesState = {
  profileVersion: 5,
  refreshing: false,
  groups: [
    {
      name: "Cuisines",
      likes: ["Korean", "Italian", "Mexican", "Middle Eastern"],
      dislikes: ["Creamy French"],
    },
    {
      name: "Ingredients",
      likes: ["Tofu", "Salmon", "Chickpeas", "Aubergine", "Lime"],
      dislikes: ["Celery", "Blue cheese"],
    },
    {
      name: "Methods",
      likes: ["Traybakes", "Stir-fries", "One-pot", "Batch cooking"],
      dislikes: ["Deep-frying"],
    },
    {
      name: "Flavour notes",
      likes: ["Gochujang heat", "Citrus", "Fresh herbs", "Smoky paprika"],
      dislikes: ["Very salty", "Overly sweet mains"],
    },
  ],
  allergies: ["Peanuts", "Tree nuts"],
  dietary: ["Maya · vegetarian"],
  lifestyle: {
    slotTimes: { breakfast: "08:00", lunch: "13:00", dinner: "19:00" },
    portionScale: 1.0,
    weeklyBudget: 55,
  },
};

/* ---- activity --------------------------------------------------------------------------- */

const activitySeed: ActivityState = {
  feedback: [
    {
      id: "f1",
      when: "Tue 9 June",
      text: "The stir fry was way too salty and honestly the portions have been small all week",
      routes: [
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
      ],
    },
    {
      id: "f2",
      when: "Sun 7 June",
      text: "Loved the shakshuka, would happily have it every week",
      routes: [
        {
          dest: "Preference",
          conf: 0.91,
          action:
            "Logged as a strong like — shakshuka weighted up in future plans.",
        },
        {
          dest: "Plan",
          conf: 0.62,
          action:
            "I could add it to next week's rotation — check this is what you meant.",
        },
      ],
    },
    {
      id: "f3",
      when: "Thu 4 June",
      text: "Friday felt rushed, dinner took way too long to cook",
      routes: [
        {
          dest: "Plan",
          conf: 0.84,
          action: "School-night dinners capped at 25 minutes going forward.",
        },
      ],
      corrected: true,
    },
  ],
  clarifications: [
    {
      id: "c-f1",
      question:
        "Is “too salty” about this one dish, or do you generally prefer less salt?",
      options: ["Just this dish", "Generally less salt", "Skip"],
      context:
        "The stir fry was way too salty and honestly the portions have been small all week",
    },
    {
      id: "c2",
      question:
        "When you say “more veg”, is that at dinner specifically or across the whole day?",
      options: ["Dinner specifically", "Across the day", "Skip"],
      context: "Could we get more veg in",
    },
  ],
};

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
    preferences: preferencesSeed,
    activity: activitySeed,
    notificationPrefs: notificationPrefsSeed,
    household: householdSeed,
    discovery: createDiscoverySeed(),
    toasts: [],
  };
}
