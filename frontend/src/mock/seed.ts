/**
 * Seed data for the mock store — ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and expanded
 * to cover every page: 12 recipes, full week plan, grocery groups, pantry
 * inventory with expiry dates, notifications.
 */

import type {
  GroceryState,
  PantryState,
  PlanCandidate,
  PlanState,
  Recipe,
  StoreState,
  TodayState,
} from "./types";

/** The mock's fixed "today" (Wednesday 10 June 2026) — keeps expiry colour
 *  coding and date labels deterministic. */
export const MOCK_TODAY_ISO = "2026-06-10";

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

/* ---- plan ------------------------------------------------------------------ */

const planSeed: PlanState = {
  title: "This week's plan",
  range: "8–14 June",
  meta: "generated Sunday · accepted from 5 candidates",
  stats: [
    { label: "Variety", value: "78%" },
    { label: "Est. cost", value: "£52 ± £4", sub: "83% confidence" },
    { label: "Protein on target", value: "5 of 7 days" },
    { label: "Quality warnings", value: "2", warn: true },
  ],
  days: [
    {
      day: "Mon",
      date: 8,
      slots: {
        breakfast: { name: "Overnight oats", state: "eaten" },
        lunch: { name: "Stir-fry", state: "eaten", batch: true },
        dinner: { name: "Salmon traybake", state: "eaten" },
      },
    },
    {
      day: "Tue",
      date: 9,
      slots: {
        breakfast: { name: "Eggs on toast", state: "eaten" },
        lunch: { name: "Stir-fry", state: "eaten", batch: true },
        dinner: { name: "Pasta norma", state: "eaten" },
      },
    },
    {
      day: "Wed",
      date: 10,
      today: true,
      slots: {
        breakfast: { name: "Overnight oats", state: "eaten" },
        lunch: { name: "Stir-fry", state: "cooked", batch: true },
        dinner: { name: "Tofu bibimbap", state: "planned" },
      },
    },
    {
      day: "Thu",
      date: 11,
      slots: {
        breakfast: { name: "Greek yoghurt bowl", state: "planned" },
        lunch: { name: "Grain bowl", state: "planned" },
        dinner: { name: "Chicken stir-fry", state: "affected" },
      },
    },
    {
      day: "Fri",
      date: 12,
      slots: {
        breakfast: { name: "Eggs on toast", state: "planned" },
        lunch: { name: "Chicken wrap", state: "affected" },
        dinner: { name: "Fish tacos", state: "planned" },
      },
    },
    {
      day: "Sat",
      date: 13,
      slots: {
        breakfast: { name: "Pancakes", state: "planned" },
        lunch: { name: "Leftover curry", state: "planned" },
        dinner: { name: "Pizza night", state: "planned" },
      },
    },
    {
      day: "Sun",
      date: 14,
      slots: {
        breakfast: { name: "Shakshuka", state: "planned" },
        lunch: { name: "Soup & bread", state: "planned" },
        dinner: { name: "Batch cook", state: "planned", batch: true },
      },
    },
  ],
  fix: {
    title: "Chicken breast marked spoiled",
    sub: "2 future slots affected · eaten and cooked meals stay pinned",
    swaps: [
      {
        day: "Thu",
        slot: "dinner",
        slotLabel: "Thu dinner",
        from: "Chicken stir-fry",
        to: "Chickpea & spinach curry",
        note: "uses expiring spinach",
      },
      {
        day: "Fri",
        slot: "lunch",
        slotLabel: "Fri lunch",
        from: "Chicken wrap",
        to: "Tuna melt",
      },
    ],
    impact: "Cost −£1.10 · protein unchanged · variety +2%",
    statsAfter: [
      { label: "Variety", value: "80%" },
      { label: "Est. cost", value: "£50.90 ± £4", sub: "83% confidence" },
      { label: "Protein on target", value: "5 of 7 days" },
      { label: "Quality warnings", value: "2", warn: true },
    ],
  },
};

/* ---- generation ------------------------------------------------------------ */

/** Base candidate values; scores are varied deterministically per round. */
export const BASE_CANDIDATES: ReadonlyArray<
  Omit<PlanCandidate, "fit" | "recommended"> & { baseFit: number }
> = [
  {
    id: 1,
    baseFit: 84,
    nutrition: "protein −6% Thu",
    cost: "£49 ± £3",
    conf: "87% confidence",
    variety: "72%",
    prep: "4h 10m",
    warn: null,
    reasoning:
      "Candidate 1 keeps cost lowest of the near-target options, but repeats last week's salmon and leaves Thursday's protein six percent short.",
    preview: [
      "Salmon traybake",
      "Veggie chilli",
      "Chicken pilaf",
      "Pasta norma",
      "Fish tacos",
      "Pizza night",
      "Batch: curry base",
    ],
  },
  {
    id: 2,
    baseFit: 91,
    nutrition: "on target all days",
    cost: "£53 ± £4",
    conf: "83% confidence",
    variety: "81%",
    prep: "3h 40m",
    warn: null,
    reasoning:
      "Candidate 2 closes last week's protein gap without repeating Monday's salmon, keeps three sub-25-minute dinners on school nights, and reuses Sunday's batch base across two lunches.",
    preview: [
      "Miso salmon traybake",
      "Black bean tacos",
      "Chicken pilaf",
      "Gnocchi al forno",
      "Prawn stir-fry",
      "Pizza night",
      "Batch: chilli base",
    ],
  },
  {
    id: 3,
    baseFit: 88,
    nutrition: "on target all days",
    cost: "£58 ± £6",
    conf: "71% confidence",
    variety: "85%",
    prep: "5h 05m",
    warn: "over budget",
    reasoning:
      "Candidate 3 maximises novelty with two cuisines you haven't cooked this month, but the basket runs three pounds over budget at current prices.",
    preview: [
      "Miso cod traybake",
      "Lamb kofta bowls",
      "Mushroom risotto",
      "Black bean tacos",
      "Prawn linguine",
      "Pizza night",
      "Batch: ragu base",
    ],
  },
  {
    id: 4,
    baseFit: 79,
    nutrition: "protein −9% Tue, Fri",
    cost: "£46 ± £3",
    conf: "88% confidence",
    variety: "64%",
    prep: "3h 15m",
    warn: "2 quality warnings",
    reasoning:
      "Candidate 4 is the cheapest and quickest week, at the cost of two quality warnings and the lowest variety of the five.",
    preview: [
      "Chicken traybake",
      "Veggie stir-fry",
      "Chicken pilaf",
      "Quesadillas",
      "Sausage bake",
      "Pizza night",
      "Batch: soup base",
    ],
  },
  {
    id: 5,
    baseFit: 76,
    nutrition: "fibre low 3 days",
    cost: "£51 ± £5",
    conf: "76% confidence",
    variety: "88%",
    prep: "4h 45m",
    warn: "1 quality warning",
    reasoning:
      "Candidate 5 pushes variety to its highest with three recipes new to your catalogue, though fibre runs low on three days.",
    preview: [
      "Prawn stir-fry",
      "Black bean tacos",
      "Shakshuka",
      "Gnocchi al forno",
      "Miso salmon traybake",
      "Pizza night",
      "Batch: chilli base",
    ],
  },
];

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

/* ---- today ----------------------------------------------------------------------- */

const todaySeed: TodayState = {
  dateLabel: "Wednesday 10 June",
  progressLabel: "week plan day 4 of 7",
  greeting: "Good evening, Iren",
  slotMeta: {
    breakfast: {
      time: "08:00",
      meta: "Just you · 380 kcal",
      kcal: 380,
    },
    lunch: {
      time: "13:00",
      meta: "Just you · cooked Sunday, portion 3 of 5",
      kcal: 520,
    },
    dinner: {
      time: "19:00",
      meta: "Shared · 4 eating · start cooking 18:35",
      kcal: 520,
      alert: "Defrost tofu by 15:00",
    },
  },
  attention: [
    {
      kind: "expiry",
      text: "Spinach expires tomorrow — used in Thursday's curry",
    },
    { kind: "defrost", text: "Defrost tofu by 15:00 for tonight" },
    { kind: "ai", text: "1 recipe suggestion waiting for review" },
  ],
  suggestion: {
    label: "Suggestion · from your feedback",
    title: "Reduce soy sauce in chicken stir-fry by 30%",
    sub: "From your feedback on Tuesday — “too salty”",
    recipeId: "chicken-stir-fry",
  },
  nutrition: [
    { label: "Calories", value: 1420, target: 2000, unit: "" },
    { label: "Protein", value: 64, target: 120, unit: " g", behind: true },
    { label: "Carbs", value: 150, target: 220, unit: " g" },
    { label: "Fat", value: 48, target: 70, unit: " g" },
  ],
};

/* ---- root ------------------------------------------------------------------------- */

export function createSeed(): StoreState {
  return {
    plan: planSeed,
    generation: {
      status: "idle",
      round: 0,
      title: "Generate next week's plan",
      context:
        "15–21 June · 2 adults, 2 children · budget £55 · 3 school-night dinners under 25 min",
      feasibility:
        "All hard constraints satisfiable — Maya's vegetarian meals and the shared dinner slots have no conflicts.",
      candidates: [],
    },
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
        title: "This week's plan accepted from 5 candidates",
        time: "Sun 7 June",
        read: true,
      },
    ],
    today: todaySeed,
  };
}
