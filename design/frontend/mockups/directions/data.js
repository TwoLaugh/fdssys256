// Shared content for all design directions — same data, different visual languages.
window.MEAL = {
  week: {
    title: "This week's plan",
    range: "8–14 June",
    meta: "generated Sunday · accepted from 5 candidates",
    stats: [
      { label: "Variety", value: "78%" },
      { label: "Est. cost", value: "£52 ± £4", sub: "83% confidence" },
      { label: "Protein on target", value: "5 of 7 days" },
      { label: "Quality warnings", value: "2", warn: true }
    ],
    fix: {
      title: "Chicken breast marked spoiled",
      sub: "2 future slots affected · eaten and cooked meals stay pinned",
      swaps: [
        { slot: "Thu dinner", from: "Chicken stir-fry", to: "Chickpea & spinach curry", note: "uses expiring spinach" },
        { slot: "Fri lunch", from: "Chicken wrap", to: "Tuna melt", note: null }
      ],
      impact: "Cost −£1.10 · protein unchanged · variety +2%"
    },
    days: [
      { d: "Mon", n: 8,  b: { name: "Overnight oats", s: "eaten" },     l: { name: "Stir-fry", s: "eaten", batch: true },  din: { name: "Salmon traybake", s: "eaten" } },
      { d: "Tue", n: 9,  b: { name: "Eggs on toast", s: "eaten" },      l: { name: "Stir-fry", s: "eaten", batch: true },  din: { name: "Pasta norma", s: "eaten" } },
      { d: "Wed", n: 10, today: true, b: { name: "Overnight oats", s: "eaten" }, l: { name: "Stir-fry", s: "cooked", batch: true }, din: { name: "Tofu bibimbap", s: "planned" } },
      { d: "Thu", n: 11, b: { name: "Greek yoghurt bowl", s: "planned" }, l: { name: "Grain bowl", s: "planned" },          din: { name: "Chicken stir-fry", s: "affected" } },
      { d: "Fri", n: 12, b: { name: "Eggs on toast", s: "planned" },    l: { name: "Chicken wrap", s: "affected" },        din: { name: "Fish tacos", s: "planned" } },
      { d: "Sat", n: 13, b: { name: "Pancakes", s: "planned" },         l: { name: "Leftover curry", s: "planned" },       din: { name: "Pizza night", s: "planned" } },
      { d: "Sun", n: 14, b: { name: "Shakshuka", s: "planned" },        l: { name: "Soup & bread", s: "planned" },         din: { name: "Batch cook", s: "planned", batch: true } }
    ]
  },
  day: {
    greeting: "Good evening, Iren",
    date: "Wednesday 10 June",
    progress: "week plan day 4 of 7",
    meals: [
      { time: "08:00", slot: "breakfast", name: "Overnight oats with berries", who: "Just you · 380 kcal", status: "eaten" },
      { time: "13:00", slot: "lunch", name: "Chicken stir-fry", who: "Just you · cooked Sunday, portion 3 of 5", status: "cooked", batch: true, action: "Mark eaten" },
      { time: "19:00", slot: "dinner", name: "Crispy tofu bibimbap", who: "Shared · 4 eating · start cooking 18:35", status: "planned", action: "Start cooking", alert: "Defrost tofu by 15:00" }
    ],
    nutrition: [
      { label: "Calories", val: 1420, max: 2000, fmt: "1,420 / 2,000" },
      { label: "Protein", val: 64, max: 120, fmt: "64 / 120 g", behind: true },
      { label: "Carbs", val: 150, max: 220, fmt: "150 / 220 g" },
      { label: "Fat", val: 48, max: 70, fmt: "48 / 70 g" }
    ],
    attention: [
      { kind: "expiry", text: "Spinach expires tomorrow — used in Thursday's curry" },
      { kind: "defrost", text: "Defrost tofu by 15:00 for tonight" },
      { kind: "ai", text: "1 recipe suggestion waiting for review" }
    ],
    budget: { spent: "£38.20", total: "£55", pct: 69.5, note: "On track · 3 days left" },
    suggestion: {
      title: "Reduce soy sauce in chicken stir-fry by 30%",
      sub: "From your feedback on Tuesday — “too salty”"
    }
  }
};
