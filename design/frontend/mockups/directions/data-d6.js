// Extra fixture data for the D6 hard-case screens: generation, feedback routing, grocery.
window.MEAL.gen = {
  title: "Generate next week's plan",
  context: "15–21 June · 2 adults, 2 children · budget £55 · 3 school-night dinners under 25 min",
  feasibility: "All hard constraints satisfiable — Maya's vegetarian meals and the shared dinner slots have no conflicts.",
  reasoning: "Candidate 2 closes last week's protein gap without repeating Monday's salmon, keeps three sub-25-minute dinners on school nights, and reuses Sunday's batch base across two lunches.",
  candidates: [
    { id: 1, fit: 84, nutrition: "protein −6% Thu", cost: "£49 ± £3", conf: "87% confidence", variety: "72%", prep: "4h 10m", warn: null },
    { id: 2, fit: 91, recommended: true, nutrition: "on target all days", cost: "£53 ± £4", conf: "83% confidence", variety: "81%", prep: "3h 40m", warn: null },
    { id: 3, fit: 88, nutrition: "on target all days", cost: "£58 ± £6", conf: "71% confidence", variety: "85%", prep: "5h 05m", warn: "over budget" },
    { id: 4, fit: 79, nutrition: "protein −9% Tue, Fri", cost: "£46 ± £3", conf: "88% confidence", variety: "64%", prep: "3h 15m", warn: "2 quality warnings" },
    { id: 5, fit: 76, nutrition: "fibre low 3 days", cost: "£51 ± £5", conf: "76% confidence", variety: "88%", prep: "4h 45m", warn: "1 quality warning" }
  ],
  preview: ["Miso salmon traybake", "Black bean tacos", "Chicken pilaf", "Gnocchi al forno", "Prawn stir-fry", "Pizza night", "Batch: chilli base"]
};

window.MEAL.feedback = {
  input: "The stir fry was way too salty and honestly the portions have been small all week",
  routes: [
    { dest: "Recipe", conf: "0.92", tier: "high",
      action: "The recipe optimiser will propose a lower-salt version of chicken stir-fry." },
    { dest: "Nutrition", conf: "0.71", tier: "mid",
      action: "Increase per-meal portion targets for dinners — I think this is what you meant." },
    { dest: "Preference", conf: "0.44", tier: "low",
      question: "Is “too salty” about this one dish, or do you generally prefer less salt?",
      options: ["Just this dish", "Generally less salt", "Skip"] }
  ],
  note: "Correcting a route teaches the classifier — corrections are tracked."
};

window.MEAL.recipe = {
  name: "Crispy tofu bibimbap",
  source: "Imported from bonappetit.com · version 3 · in your catalogue",
  img: "https://images.unsplash.com/photo-1553163147-622ab57be1c7?w=900&q=60",
  chips: ["25 min", "Serves 4", "Korean", "User verified"],
  ratings: [
    { label: "Taste", val: 86 },
    { label: "Worth the effort", val: 78 },
    { label: "Portion fit", val: 90 },
    { label: "Would repeat", val: 81 }
  ],
  ingredients: [
    { n: "Firm tofu", q: "400 g" },
    { n: "Short-grain rice", q: "300 g" },
    { n: "Soy sauce", q: "3 tbsp", swap: "swap: tamari" },
    { n: "Spinach", q: "150 g" },
    { n: "Carrots, julienned", q: "2" },
    { n: "Gochujang", q: "2 tbsp" }
  ],
  moreIngredients: "+ 5 more",
  steps: [
    "Press the tofu for 15 minutes, then cube and toss in cornflour.",
    "Cook the rice. Meanwhile fry the tofu until crisp on all sides.",
    "Blanch the spinach, dress with sesame; sauté the carrots briefly."
  ],
  moreSteps: "+ 4 more steps",
  pending: {
    title: "Reduce soy sauce by 30%",
    sub: "From your feedback Tuesday · confidence 0.88 · creates version 4 if accepted",
    from: "3 tbsp soy sauce", to: "2 tbsp soy sauce"
  },
  versions: ["v3 current", "v2", "v1"],
  nutrition: ["520 kcal", "28 g protein", "55 g carbs", "18 g fat"]
};

window.MEAL.grocery = {
  stats: [
    { label: "Projected total", value: "£47.30 ± £3.10", sub: "83% confidence" },
    { label: "Items bought", value: "9 of 23" },
    { label: "Stale prices", value: "4", sub: "not updated in 2 weeks", warn: true },
    { label: "Budget headroom", value: "£7.70", sub: "vs £55 weekly" }
  ],
  order: {
    provider: "Tesco delivery", state: "Confirmed", eta: "Sat 13 June · 10–11am",
    steps: ["Draft", "Quoted", "Placed", "Confirmed", "Delivered"], at: 3
  },
  substitution: {
    from: "Gochujang paste 200 g", to: "Red pepper paste 180 g",
    reason: "out of stock at Tesco", delta: "−£0.40"
  },
  groups: [
    { name: "Produce", items: [
      { n: "Spinach", q: "300 g", price: "£1.80", state: "bought" },
      { n: "Carrots", q: "1 kg", price: "£0.85", state: "bought" },
      { n: "Spring onions", q: "1 bunch", price: "£0.75", state: "open" },
      { n: "Fresh basil", q: "1 bunch", price: "£1.20", state: "open", stale: true }
    ]},
    { name: "Protein & dairy", items: [
      { n: "Firm tofu", q: "2 × 400 g", price: "£4.40", state: "open" },
      { n: "Tuna (tinned)", q: "3 tins", price: "£3.30", state: "open", note: "added by suggested fix" },
      { n: "Greek yoghurt", q: "1 kg", price: "£2.60", state: "bought" },
      { n: "Eggs", q: "12", price: "£2.95", state: "open", stale: true }
    ]},
    { name: "Pantry", items: [
      { n: "Short-grain rice", q: "1 kg", price: "£2.10", state: "bought" },
      { n: "Soy sauce (low salt)", q: "250 ml", price: "£1.85", state: "open", note: "swapped after feedback" },
      { n: "Chickpeas", q: "2 tins", price: "£1.30", state: "open" }
    ]}
  ]
};
