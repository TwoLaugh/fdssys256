/**
 * Seed data for the mock store — ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and expanded
 * to cover every page: 12 recipes, full week plan, grocery groups, pantry
 * inventory with expiry dates, notifications.
 */

import { createNutritionSeed, targetsSeed } from "./nutritionSeed";
import { createPlannerSeed } from "./plannerSeed";
import type {
  ActivityState,
  AdaptationState,
  DiscoveryResult,
  DiscoverySource,
  DiscoveryState,
  GroceryState,
  HouseholdState,
  NotificationPrefs,
  PantryState,
  PreferencesState,
  Recipe,
  StoreState,
} from "./types";

/** The mock's fixed "today" (Wednesday 10 June 2026) — keeps expiry colour
 *  coding and date labels deterministic. Defined with the nutrition seed,
 *  whose intake days are keyed on the same week. */
export { MOCK_TODAY_ISO } from "./nutritionSeed";

/* ---- photography ----------------------------------------------------------
 * A small pool of food photos reused across cards; every <img> falls back to
 * the warm #e8dcc8 swatch on error (design-language: photography section).
 */
const IMG_BOWL =
  "https://images.unsplash.com/photo-1553163147-622ab57be1c7?w=900&q=60";
const IMG_PLATE =
  "https://images.unsplash.com/photo-1512058564366-18510be2db19?w=900&q=60";
const IMG_SALMON =
  "https://images.unsplash.com/photo-1467003909585-2f8a72700288?w=900&q=60";
const IMG_TACOS =
  "https://images.unsplash.com/photo-1565299585323-38d6b0865b47?w=900&q=60";

/* ---- recipes ---------------------------------------------------------------- */

const recipesSeed: Recipe[] = [
  {
    id: "tofu-bibimbap",
    name: "Crispy tofu bibimbap",
    cuisine: "Korean",
    timeMin: 25,
    serves: 4,
    taste: 86,
    tier: "user verified",
    img: IMG_BOWL,
    source: "Imported from bonappetit.com · version 3 · in your catalogue",
    ratings: [
      { label: "Taste", val: 86 },
      { label: "Worth the effort", val: 78 },
      { label: "Portion fit", val: 90 },
      { label: "Would repeat", val: 81 },
    ],
    nutrition: ["520 kcal", "28 g protein", "55 g carbs", "18 g fat"],
    ingredients: [
      { n: "Firm tofu", q: "400 g" },
      { n: "Short-grain rice", q: "300 g" },
      { n: "Soy sauce", q: "3 tbsp", swap: "swap: tamari" },
      { n: "Spinach", q: "150 g" },
      { n: "Carrots, julienned", q: "2" },
      { n: "Gochujang", q: "2 tbsp" },
    ],
    moreIngredients: "+ 5 more",
    steps: [
      "Press the tofu for 15 minutes, then cube and toss in cornflour.",
      "Cook the rice. Meanwhile fry the tofu until crisp on all sides.",
      "Blanch the spinach, dress with sesame; sauté the carrots briefly.",
    ],
    moreSteps: "+ 4 more steps",
    versions: ["v3 current", "v2", "v1"],
    pendingChange: {
      title: "Reduce soy sauce by 30%",
      sub: "From your feedback Tuesday · confidence 0.88 · creates version 4 if accepted",
      from: "3 tbsp soy sauce",
      to: "2 tbsp soy sauce",
      ingredient: "Soy sauce",
      newQty: "2 tbsp",
    },
  },
  {
    id: "chicken-stir-fry",
    name: "Chicken stir-fry",
    cuisine: "Chinese",
    timeMin: 20,
    serves: 4,
    taste: 82,
    tier: "user verified",
    img: IMG_PLATE,
    source: "Your recipe · version 1 · batch-cook favourite",
    ratings: [
      { label: "Taste", val: 82 },
      { label: "Worth the effort", val: 88 },
      { label: "Portion fit", val: 84 },
      { label: "Would repeat", val: 86 },
    ],
    nutrition: ["480 kcal", "34 g protein", "42 g carbs", "16 g fat"],
    ingredients: [
      { n: "Chicken breast", q: "500 g" },
      { n: "Egg noodles", q: "300 g" },
      { n: "Soy sauce", q: "3 tbsp" },
      { n: "Broccoli", q: "1 head" },
      { n: "Ginger, grated", q: "20 g" },
    ],
    steps: [
      "Slice the chicken thinly and velvet in cornflour and a little soy.",
      "Stir-fry the chicken hard in two batches; set aside.",
      "Fry the vegetables, return the chicken, add sauce and noodles.",
    ],
    versions: ["v1 current"],
    pendingChange: {
      title: "Reduce soy sauce by 30%",
      sub: "From your feedback on Tuesday — “too salty” · creates version 2 if accepted",
      from: "3 tbsp soy sauce",
      to: "2 tbsp soy sauce",
      ingredient: "Soy sauce",
      newQty: "2 tbsp",
    },
  },
  {
    id: "salmon-traybake",
    name: "Salmon traybake",
    cuisine: "British",
    timeMin: 35,
    serves: 2,
    taste: 88,
    tier: "imported",
    img: IMG_SALMON,
    source: "Imported from bbcgoodfood.com · version 1",
    ratings: [
      { label: "Taste", val: 88 },
      { label: "Worth the effort", val: 90 },
      { label: "Portion fit", val: 82 },
      { label: "Would repeat", val: 85 },
    ],
    nutrition: ["560 kcal", "36 g protein", "38 g carbs", "26 g fat"],
    ingredients: [
      { n: "Salmon fillets", q: "2" },
      { n: "New potatoes", q: "400 g" },
      { n: "Tenderstem broccoli", q: "200 g" },
      { n: "Lemon", q: "1" },
      { n: "Olive oil", q: "2 tbsp" },
    ],
    steps: [
      "Roast the potatoes with oil for 20 minutes at 200°C.",
      "Add the broccoli and salmon, season, and roast 12 minutes more.",
      "Finish with lemon zest and a squeeze of juice.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
  {
    id: "pasta-norma",
    name: "Pasta alla norma",
    cuisine: "Italian",
    timeMin: 30,
    serves: 4,
    taste: 79,
    tier: "imported",
    img: IMG_PLATE,
    source: "Imported from seriouseats.com · version 2",
    ratings: [
      { label: "Taste", val: 79 },
      { label: "Worth the effort", val: 74 },
      { label: "Portion fit", val: 80 },
      { label: "Would repeat", val: 72 },
    ],
    nutrition: ["540 kcal", "18 g protein", "74 g carbs", "19 g fat"],
    ingredients: [
      { n: "Rigatoni", q: "400 g" },
      { n: "Aubergines", q: "2" },
      { n: "Tomato passata", q: "500 g" },
      { n: "Ricotta salata", q: "80 g" },
      { n: "Basil", q: "1 bunch" },
    ],
    steps: [
      "Salt the aubergine cubes, then fry until deeply golden.",
      "Simmer the passata with garlic; fold in the aubergine.",
      "Toss with pasta and finish with ricotta salata and basil.",
    ],
    versions: ["v2 current", "v1"],
    pendingChange: null,
  },
  {
    id: "chickpea-spinach-curry",
    name: "Chickpea & spinach curry",
    cuisine: "Indian",
    timeMin: 25,
    serves: 4,
    taste: 84,
    tier: "ai generated",
    img: IMG_BOWL,
    source: "Generated for your pantry · version 1",
    ratings: [
      { label: "Taste", val: 84 },
      { label: "Worth the effort", val: 86 },
      { label: "Portion fit", val: 78 },
      { label: "Would repeat", val: 80 },
    ],
    nutrition: ["430 kcal", "19 g protein", "58 g carbs", "14 g fat"],
    ingredients: [
      { n: "Chickpeas", q: "2 tins" },
      { n: "Spinach", q: "200 g" },
      { n: "Coconut milk", q: "400 ml" },
      { n: "Curry paste", q: "3 tbsp" },
      { n: "Basmati rice", q: "300 g" },
    ],
    steps: [
      "Fry the curry paste until fragrant, then add the chickpeas.",
      "Pour in the coconut milk and simmer for 10 minutes.",
      "Wilt in the spinach and serve over rice.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
  {
    id: "fish-tacos",
    name: "Fish tacos",
    cuisine: "Mexican",
    timeMin: 25,
    serves: 4,
    taste: 81,
    tier: "imported",
    img: IMG_TACOS,
    source: "Imported from bonappetit.com · version 1",
    ratings: [
      { label: "Taste", val: 81 },
      { label: "Worth the effort", val: 76 },
      { label: "Portion fit", val: 83 },
      { label: "Would repeat", val: 79 },
    ],
    nutrition: ["470 kcal", "27 g protein", "48 g carbs", "17 g fat"],
    ingredients: [
      { n: "White fish fillets", q: "500 g" },
      { n: "Corn tortillas", q: "12" },
      { n: "Red cabbage", q: "¼ head" },
      { n: "Lime", q: "2" },
      { n: "Soured cream", q: "150 ml" },
    ],
    steps: [
      "Dust the fish in spiced flour and pan-fry until just done.",
      "Shred the cabbage and dress with lime and a pinch of salt.",
      "Warm the tortillas and assemble with cream and hot sauce.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
  {
    id: "tuna-melt",
    name: "Tuna melt",
    cuisine: "American",
    timeMin: 15,
    serves: 2,
    taste: 74,
    tier: "user verified",
    img: IMG_PLATE,
    source: "Your recipe · version 1",
    ratings: [
      { label: "Taste", val: 74 },
      { label: "Worth the effort", val: 92 },
      { label: "Portion fit", val: 76 },
      { label: "Would repeat", val: 71 },
    ],
    nutrition: ["520 kcal", "31 g protein", "40 g carbs", "24 g fat"],
    ingredients: [
      { n: "Tuna (tinned)", q: "2 tins" },
      { n: "Sourdough", q: "4 slices" },
      { n: "Mature cheddar", q: "100 g" },
      { n: "Spring onions", q: "3" },
      { n: "Mayonnaise", q: "3 tbsp" },
    ],
    steps: [
      "Mix the tuna with mayo, spring onion and black pepper.",
      "Pile onto the bread, top with cheddar.",
      "Grill until bubbling and golden.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
  {
    id: "shakshuka",
    name: "Shakshuka",
    cuisine: "Middle Eastern",
    timeMin: 30,
    serves: 4,
    taste: 85,
    tier: "web discovered",
    img: IMG_BOWL,
    source: "Discovered from ottolenghi.co.uk · version 1",
    ratings: [
      { label: "Taste", val: 85 },
      { label: "Worth the effort", val: 80 },
      { label: "Portion fit", val: 79 },
      { label: "Would repeat", val: 83 },
    ],
    nutrition: ["390 kcal", "21 g protein", "26 g carbs", "23 g fat"],
    ingredients: [
      { n: "Eggs", q: "6" },
      { n: "Tomato passata", q: "500 g" },
      { n: "Red peppers", q: "2" },
      { n: "Cumin", q: "2 tsp" },
      { n: "Feta", q: "80 g" },
    ],
    steps: [
      "Soften the peppers with onion and cumin.",
      "Add the passata and reduce to a thick sauce.",
      "Crack in the eggs, cover, and cook until just set.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
  {
    id: "miso-salmon-traybake",
    name: "Miso salmon traybake",
    cuisine: "Japanese",
    timeMin: 30,
    serves: 4,
    taste: 87,
    tier: "web discovered",
    img: IMG_SALMON,
    source: "Discovered from justonecookbook.com · version 1",
    ratings: [
      { label: "Taste", val: 87 },
      { label: "Worth the effort", val: 84 },
      { label: "Portion fit", val: 81 },
      { label: "Would repeat", val: 86 },
    ],
    nutrition: ["545 kcal", "35 g protein", "41 g carbs", "25 g fat"],
    ingredients: [
      { n: "Salmon fillets", q: "4" },
      { n: "White miso", q: "2 tbsp" },
      { n: "Sweet potatoes", q: "500 g" },
      { n: "Pak choi", q: "2 heads" },
      { n: "Sesame seeds", q: "1 tbsp" },
    ],
    steps: [
      "Roast the sweet potato wedges for 20 minutes.",
      "Brush the salmon with miso glaze; add with the pak choi.",
      "Roast 12 minutes more and scatter with sesame.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
  {
    id: "black-bean-tacos",
    name: "Black bean tacos",
    cuisine: "Mexican",
    timeMin: 20,
    serves: 4,
    taste: 80,
    tier: "ai generated",
    img: IMG_TACOS,
    source: "Generated for Maya's vegetarian nights · version 1",
    ratings: [
      { label: "Taste", val: 80 },
      { label: "Worth the effort", val: 89 },
      { label: "Portion fit", val: 82 },
      { label: "Would repeat", val: 78 },
    ],
    nutrition: ["410 kcal", "16 g protein", "57 g carbs", "13 g fat"],
    ingredients: [
      { n: "Black beans", q: "2 tins" },
      { n: "Corn tortillas", q: "12" },
      { n: "Avocado", q: "2" },
      { n: "Pickled red onion", q: "80 g" },
      { n: "Smoked paprika", q: "2 tsp" },
    ],
    steps: [
      "Fry the beans with paprika, mashing roughly as they warm.",
      "Smash the avocado with lime and salt.",
      "Build the tacos and top with pickled onion.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
  {
    id: "gnocchi-al-forno",
    name: "Gnocchi al forno",
    cuisine: "Italian",
    timeMin: 35,
    serves: 4,
    taste: 83,
    tier: "imported",
    img: IMG_PLATE,
    source: "Imported from bbcgoodfood.com · version 1",
    ratings: [
      { label: "Taste", val: 83 },
      { label: "Worth the effort", val: 77 },
      { label: "Portion fit", val: 85 },
      { label: "Would repeat", val: 81 },
    ],
    nutrition: ["580 kcal", "22 g protein", "78 g carbs", "21 g fat"],
    ingredients: [
      { n: "Gnocchi", q: "800 g" },
      { n: "Tomato passata", q: "500 g" },
      { n: "Mozzarella", q: "250 g" },
      { n: "Parmesan", q: "40 g" },
      { n: "Basil", q: "1 bunch" },
    ],
    steps: [
      "Simmer the passata with garlic and a pinch of sugar.",
      "Fold in the gnocchi and half the mozzarella.",
      "Top with the rest and bake until blistered.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
  {
    id: "prawn-stir-fry",
    name: "Prawn stir-fry",
    cuisine: "Thai",
    timeMin: 18,
    serves: 2,
    taste: 82,
    tier: "imported",
    img: IMG_BOWL,
    source: "Imported from seriouseats.com · version 1",
    ratings: [
      { label: "Taste", val: 82 },
      { label: "Worth the effort", val: 90 },
      { label: "Portion fit", val: 77 },
      { label: "Would repeat", val: 80 },
    ],
    nutrition: ["440 kcal", "30 g protein", "46 g carbs", "14 g fat"],
    ingredients: [
      { n: "Raw king prawns", q: "250 g" },
      { n: "Rice noodles", q: "200 g" },
      { n: "Fish sauce", q: "2 tbsp" },
      { n: "Sugar snap peas", q: "150 g" },
      { n: "Chilli", q: "1" },
    ],
    steps: [
      "Soak the noodles; flash-fry the prawns and set aside.",
      "Stir-fry the vegetables with chilli and garlic.",
      "Return the prawns with the sauce and toss through the noodles.",
    ],
    versions: ["v1 current"],
    pendingChange: null,
  },
];

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

/* ---- discovery -------------------------------------------------------------------------------- */

/** Canned result set applied when a discovery job reaches DONE. */
export const DISCOVERY_RESULTS: ReadonlyArray<Omit<DiscoveryResult, "status">> =
  [
    {
      id: "d1",
      title: "Harissa chickpea traybake",
      domain: "bbcgoodfood.com",
      conf: 0.93,
      timeMin: 30,
      cuisine: "Middle Eastern",
    },
    {
      id: "d2",
      title: "Peanut-free satay noodles",
      domain: "seriouseats.com",
      conf: 0.88,
      timeMin: 25,
      cuisine: "Thai",
    },
    {
      id: "d3",
      title: "Charred corn & black bean salad",
      domain: "budgetbytes.com",
      conf: 0.81,
      timeMin: 20,
      cuisine: "Mexican",
    },
    {
      id: "d4",
      title: "Miso aubergine rice bowls",
      domain: "bbcgoodfood.com",
      conf: 0.74,
      timeMin: 35,
      cuisine: "Japanese",
    },
    {
      id: "d5",
      title: "Spiced lamb flatbreads",
      domain: "ottolenghi.co.uk",
      conf: 0.58,
      timeMin: 40,
      cuisine: "Middle Eastern",
    },
  ];

/** Per-source transparency: pages scanned per domain for a finished job. */
export const DISCOVERY_SOURCES: ReadonlyArray<DiscoverySource> = [
  { domain: "bbcgoodfood.com", hits: 14 },
  { domain: "seriouseats.com", hits: 9 },
  { domain: "budgetbytes.com", hits: 7 },
  { domain: "ottolenghi.co.uk", hits: 4 },
];

/** Image pool for recipes kept from discovery. */
export const DISCOVERY_IMGS: ReadonlyArray<string> = [
  IMG_BOWL,
  IMG_PLATE,
  IMG_TACOS,
  IMG_SALMON,
];

const discoverySeed: DiscoveryState = {
  job: null,
  history: [
    { query: "vegetarian one-pot dinners", when: "Sun 7 June", found: 6, kept: 2 },
    { query: "high-protein breakfasts", when: "Tue 2 June", found: 5, kept: 1 },
  ],
};

/* ---- root ------------------------------------------------------------------------- */

/* ---- adaptation (Today's suggestion teaser, today.md §3f) --------------------------- */

const adaptationSeed: AdaptationState = {
  pendingChanges: [
    {
      id: "pc-1",
      recipeId: "chicken-stir-fry",
      changeDimension: "SALT_LEVEL",
      reasoningPreview:
        "Reduce soy sauce in chicken stir-fry by 30% — from your feedback on Tuesday, “too salty”",
      confidence: 0.88,
      impactScore: 0.72,
      createdAt: "2026-06-09T20:15:00Z",
      expiresAt: "2026-06-13T20:15:00Z",
    },
  ],
};

export function createSeed(): StoreState {
  return {
    planner: createPlannerSeed(),
    adaptation: adaptationSeed,
    recipes: recipesSeed,
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
    discovery: discoverySeed,
    toasts: [],
  };
}
