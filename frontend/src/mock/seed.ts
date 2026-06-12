/**
 * Seed data for the mock store — ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and expanded
 * to cover every page: 12 recipes, full week plan, grocery groups, pantry
 * inventory with expiry dates, notifications.
 */

import { createNutritionSeed, targetsSeed } from "./nutritionSeed";
import { createPlannerSeed } from "./plannerSeed";
import {
  createAdaptationSeed,
  createDiscoverySeed,
  createRecipeSeed,
} from "./recipeSeed";
import type {
  ActivityState,
  GroceryState,
  HouseholdState,
  NotificationPrefs,
  PantryState,
  PreferencesState,
  StoreState,
} from "./types";

/** The mock's fixed "today" (Wednesday 10 June 2026) — keeps expiry colour
 *  coding and date labels deterministic. Defined with the nutrition seed,
 *  whose intake days are keyed on the same week. */
export { MOCK_TODAY_ISO } from "./nutritionSeed";

/* ---- grocery ----------------------------------------------------------------- */

const grocerySeed: GroceryState = {
  contextLine: "From this week's plan · recalculated after the Thursday fix",
  projectedTotal: "£47.30 ± £3.10",
  projectedConf: "83% confidence",
  headroom: "£7.70",
  headroomSub: "vs £55 weekly",
  groups: [
    {
      name: "Produce",
      items: [
        { n: "Spinach", q: "300 g", price: "£1.80", state: "bought" },
        { n: "Carrots", q: "1 kg", price: "£0.85", state: "bought" },
        {
          n: "Spring onions",
          q: "1 bunch",
          price: "£0.75",
          state: "open",
          stale: true,
        },
        {
          n: "Fresh basil",
          q: "1 bunch",
          price: "£1.20",
          state: "open",
          stale: true,
        },
      ],
    },
    {
      name: "Protein & dairy",
      items: [
        { n: "Firm tofu", q: "2 × 400 g", price: "£4.40", state: "open" },
        {
          n: "Tuna (tinned)",
          q: "3 tins",
          price: "£3.30",
          state: "open",
          note: "added by suggested fix",
        },
        { n: "Greek yoghurt", q: "1 kg", price: "£2.60", state: "bought" },
        { n: "Eggs", q: "12", price: "£2.95", state: "open", stale: true },
      ],
    },
    {
      name: "Pantry",
      items: [
        { n: "Short-grain rice", q: "1 kg", price: "£2.10", state: "bought" },
        {
          n: "Soy sauce (low salt)",
          q: "250 ml",
          price: "£1.85",
          state: "open",
          note: "swapped after feedback",
        },
        {
          n: "Chickpeas",
          q: "2 tins",
          price: "£1.30",
          state: "open",
          stale: true,
        },
        { n: "Gochujang paste", q: "200 g", price: "£2.80", state: "open" },
      ],
    },
  ],
  order: {
    provider: "Tesco delivery",
    state: "Confirmed",
    eta: "Sat 13 June · 10–11am",
    steps: ["Draft", "Quoted", "Placed", "Confirmed", "Delivered"],
    at: 3,
  },
  substitution: {
    from: "Gochujang paste 200 g",
    to: "Red pepper paste 180 g",
    reason: "out of stock at Tesco",
    delta: "−£0.40",
    targetItem: "Gochujang paste",
    replacement: { n: "Red pepper paste", q: "180 g", price: "£2.40" },
  },
};

/* ---- pantry -------------------------------------------------------------------- */

const pantrySeed: PantryState = {
  items: [
    // fridge
    {
      id: "spinach",
      name: "Spinach",
      location: "fridge",
      qty: 150,
      unit: "g",
      expiry: "2026-06-11",
      estCost: 1.8,
    },
    {
      id: "greek-yoghurt",
      name: "Greek yoghurt",
      location: "fridge",
      qty: 1,
      unit: "kg",
      expiry: "2026-06-16",
      estCost: 2.6,
    },
    {
      id: "firm-tofu",
      name: "Firm tofu",
      location: "fridge",
      qty: 2,
      unit: "× 400 g",
      expiry: "2026-06-15",
      estCost: 4.4,
    },
    {
      id: "eggs",
      name: "Eggs",
      location: "fridge",
      qty: 9,
      unit: "",
      expiry: "2026-06-24",
      estCost: 2.95,
    },
    {
      id: "chicken-breast",
      name: "Chicken breast",
      location: "fridge",
      qty: 0,
      unit: "g",
      expiry: "2026-06-09",
      estCost: 3.1,
      spoiled: true,
    },
    // freezer
    {
      id: "frozen-peas",
      name: "Frozen peas",
      location: "freezer",
      qty: 900,
      unit: "g",
      expiry: "2026-09-01",
      estCost: 1.2,
    },
    {
      id: "batch-chilli",
      name: "Batch chilli base",
      location: "freezer",
      qty: 3,
      unit: "portions",
      expiry: "2026-07-10",
      estCost: 4.5,
    },
    {
      id: "prawns",
      name: "King prawns",
      location: "freezer",
      qty: 250,
      unit: "g",
      expiry: "2026-08-15",
      estCost: 3.75,
    },
    // pantry
    {
      id: "rice",
      name: "Short-grain rice",
      location: "pantry",
      qty: 1,
      unit: "kg",
      expiry: "2027-01-15",
      estCost: 2.1,
    },
    {
      id: "chickpeas",
      name: "Chickpeas",
      location: "pantry",
      qty: 4,
      unit: "tins",
      expiry: "2026-12-30",
      estCost: 2.6,
    },
    {
      id: "soy-sauce",
      name: "Soy sauce",
      location: "pantry",
      qty: 250,
      unit: "ml",
      expiry: "2026-11-20",
      estCost: 1.85,
    },
    {
      id: "gochujang",
      name: "Gochujang",
      location: "pantry",
      qty: 1,
      unit: "jar",
      expiry: "2026-10-05",
      estCost: 2.8,
    },
  ],
  equipment: [
    "Slow cooker",
    "Air fryer",
    "Stick blender",
    "Rice cooker",
    "Cast-iron pan",
  ],
  waste: {
    monthTotal: 4.2,
    entries: [
      { name: "Chicken breast 500 g", cost: "£3.10", when: "Wed 10 June" },
      { name: "Half cucumber", cost: "£0.45", when: "Sun 7 June" },
      { name: "Coriander bunch", cost: "£0.65", when: "Tue 2 June" },
    ],
  },
  budget: { spent: 38.2, total: 55, note: "On track · 3 days left" },
};

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
    grocery: grocerySeed,
    pantry: pantrySeed,
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
