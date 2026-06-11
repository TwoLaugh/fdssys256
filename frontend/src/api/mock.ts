/**
 * Fixture-mode data source for the Today page.
 *
 * Active by default whenever VITE_API_BASE is unset, so `npm run dev` works
 * with zero backend. Fixtures match the D6 design-direction mockups
 * (food-system-designs/directions/data.js).
 */

import type {
  AdvisorSuggestion,
  NotificationsSummary,
  NutritionStat,
  PlanToday,
  TodayDataSource,
  WeekBudget,
} from "./today";

const planToday: PlanToday = {
  dateLabel: "Wednesday 10 June",
  progressLabel: "week plan day 4 of 7",
  greeting: "Good evening, Iren",
  planActive: true,
  meals: [
    {
      time: "08:00",
      slot: "breakfast",
      name: "Overnight oats with berries",
      meta: "Just you · 380 kcal",
      status: "eaten",
    },
    {
      time: "13:00",
      slot: "lunch",
      name: "Chicken stir-fry",
      meta: "Just you · cooked Sunday, portion 3 of 5",
      status: "cooked",
      batch: true,
      action: "Mark eaten",
    },
    {
      time: "19:00",
      slot: "dinner",
      name: "Crispy tofu bibimbap",
      meta: "Shared · 4 eating · start cooking 18:35",
      status: "planned",
      action: "Start cooking",
      alert: "Defrost tofu by 15:00",
    },
  ],
};

const nutrition: NutritionStat[] = [
  {
    label: "Calories",
    value: 1420,
    target: 2000,
    display: "1,420",
    targetDisplay: "2,000",
  },
  {
    label: "Protein",
    value: 64,
    target: 120,
    display: "64",
    targetDisplay: "120 g",
    behind: true,
  },
  {
    label: "Carbs",
    value: 150,
    target: 220,
    display: "150",
    targetDisplay: "220 g",
  },
  {
    label: "Fat",
    value: 48,
    target: 70,
    display: "48",
    targetDisplay: "70 g",
  },
];

const notifications: NotificationsSummary = {
  unread: 3,
  attention: [
    {
      kind: "expiry",
      text: "Spinach expires tomorrow — used in Thursday's curry",
    },
    { kind: "defrost", text: "Defrost tofu by 15:00 for tonight" },
    { kind: "ai", text: "1 recipe suggestion waiting for review" },
  ],
};

const budget: WeekBudget = {
  spentDisplay: "£38.20",
  totalDisplay: "£55",
  pct: 69.5,
  note: "On track · 3 days left",
};

const suggestion: AdvisorSuggestion = {
  label: "Suggestion · from your feedback",
  title: "Reduce soy sauce in chicken stir-fry by 30%",
  sub: "From your feedback on Tuesday — “too salty”",
};

/** Small artificial latency so loading states are visible in dev. */
function fixture<T>(data: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(data), 150));
}

export const mockTodayApi: TodayDataSource = {
  getActivePlanToday: () => fixture(planToday),
  getNutritionToday: () => fixture(nutrition),
  getNotificationsSummary: () => fixture(notifications),
  getWeekBudget: () => fixture(budget),
  getTopPendingChange: () => fixture(suggestion),
};
