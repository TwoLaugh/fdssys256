/**
 * Live data source for the Today page, backed by the real MealPrep API via
 * the typed fetch wrapper and the OpenAPI-generated types.
 *
 * Scaffold-level mapping: each method is a thin, defensive projection from
 * backend DTOs to the Today view-model. Known gaps (deliberate for now):
 * - Scheduled recipes are shown by slot label / recipe id; recipe-name
 *   hydration (GET /recipes/{id}) comes with the Plan page work.
 * - Attention items are derived from notification counts; per-notification
 *   detail rendering comes with the Activity/Notifications pages.
 */

import { api, ApiError } from "./client";
import type { components } from "./types.gen";
import type {
  AdvisorSuggestion,
  AttentionItem,
  MealStatus,
  NotificationsSummary,
  NutritionStat,
  PlanToday,
  TodayDataSource,
  TodayMeal,
  WeekBudget,
} from "./today";

type HouseholdDto = components["schemas"]["HouseholdDto"];
type PlanDto = components["schemas"]["PlanDto"];
type MealSlotDto = components["schemas"]["MealSlotDto"];
type IntakeDayDto = components["schemas"]["IntakeDayDto"];
type TargetsDto = components["schemas"]["TargetsDto"];
type NotificationSummaryDto = components["schemas"]["NotificationSummaryDto"];
type BudgetDto = components["schemas"]["BudgetDto"];
type ReoptSuggestionDtoPage = components["schemas"]["ReoptSuggestionDtoPage"];

/* ---- date helpers -------------------------------------------------------- */

function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** Monday of the week containing `d` (backend weeks start Monday). */
function weekStartOf(d: Date): string {
  const copy = new Date(d);
  const offset = (copy.getDay() + 6) % 7; // Mon=0 .. Sun=6
  copy.setDate(copy.getDate() - offset);
  return isoDate(copy);
}

function greetingForNow(): string {
  const h = new Date().getHours();
  if (h < 12) return "Good morning";
  if (h < 18) return "Good afternoon";
  return "Good evening";
}

/* ---- shared household lookup (cached per page load) ----------------------- */

let householdPromise: Promise<HouseholdDto> | null = null;

function currentHousehold(): Promise<HouseholdDto> {
  householdPromise ??= api<HouseholdDto>("/api/v1/households/current");
  return householdPromise;
}

/* ---- mapping -------------------------------------------------------------- */

function slotStatus(state: MealSlotDto["state"]): MealStatus {
  switch (state) {
    case "EATEN":
      return "eaten";
    case "COOKED":
    case "COOKING":
      return "cooked";
    default:
      return "planned";
  }
}

function toMeal(slot: MealSlotDto, isNextPlanned: boolean): TodayMeal {
  const status = slotStatus(slot.state);
  const recipe = slot.scheduledRecipe;
  const name = recipe
    ? `Recipe ${recipe.recipeId.slice(0, 8)}` // TODO: hydrate recipe names with the Plan page work
    : "Nothing scheduled";
  const metaParts = [
    slot.shared ? `Shared · ${slot.eaters.length} eating` : "Just you",
  ];
  if (recipe && recipe.servings > 1) {
    metaParts.push(`${recipe.servings} servings`);
  }

  const meal: TodayMeal = {
    time: slot.mealTime ? slot.mealTime.slice(0, 5) : "—",
    slot: slot.label.toLowerCase(),
    name,
    meta: metaParts.join(" · "),
    status,
  };
  if (recipe?.batchCookSessionId) {
    meal.batch = true;
  }
  if (status === "cooked") {
    meal.action = "Mark eaten";
  } else if (status === "planned" && isNextPlanned) {
    meal.action = "Start cooking";
  }
  return meal;
}

/* ---- data source ----------------------------------------------------------- */

export const liveTodayApi: TodayDataSource = {
  async getActivePlanToday(): Promise<PlanToday> {
    const today = new Date();
    const todayIso = isoDate(today);
    const household = await currentHousehold();
    const plan = await api<PlanDto>(
      `/api/v1/plans/active?householdId=${household.id}&weekStartDate=${weekStartOf(today)}`,
    );

    const day = plan.days.find((d) => d.date === todayIso);
    const dayIndex = plan.days.findIndex((d) => d.date === todayIso);
    const slots = [...(day?.slots ?? [])].sort(
      (a, b) => a.slotIndex - b.slotIndex,
    );
    const firstPlannedId = slots.find((s) => s.state === "PLANNED")?.id;

    return {
      dateLabel: today.toLocaleDateString("en-GB", {
        weekday: "long",
        day: "numeric",
        month: "long",
      }),
      progressLabel:
        dayIndex >= 0
          ? `week plan day ${dayIndex + 1} of ${plan.days.length}`
          : "no plan day for today",
      greeting: greetingForNow(),
      planActive: plan.status === "ACTIVE",
      meals: slots.map((s) => toMeal(s, s.id === firstPlannedId)),
    };
  },

  async getNutritionToday(): Promise<NutritionStat[]> {
    const todayIso = isoDate(new Date());
    const [intake, targets] = await Promise.all([
      api<IntakeDayDto>(`/api/v1/nutrition/intake/${todayIso}`),
      api<TargetsDto>("/api/v1/nutrition/targets"),
    ]);

    let calories = 0;
    let protein = 0;
    let carbs = 0;
    let fat = 0;
    for (const slot of intake.slots) {
      // Pending slots have no actuals yet; count only what was confirmed,
      // overridden, or edited.
      if (slot.actual.status === "PENDING" || slot.actual.status === "SKIPPED")
        continue;
      calories += slot.actual.calories ?? 0;
      protein += slot.actual.proteinG ?? 0;
      carbs += slot.actual.carbsG ?? 0;
      fat += slot.actual.fatG ?? 0;
    }
    for (const snack of intake.snacks) {
      calories += snack.calories;
      protein += snack.proteinG;
      carbs += snack.carbsG;
      fat += snack.fatG;
    }

    const dayFraction = new Date().getHours() / 24;
    const stat = (
      label: string,
      value: number,
      target: number,
      unit: string,
    ): NutritionStat => {
      const rounded = Math.round(value);
      const behind = target > 0 && value / target < dayFraction - 0.15;
      return {
        label,
        value: rounded,
        target,
        display: rounded.toLocaleString("en-GB"),
        targetDisplay: `${target.toLocaleString("en-GB")}${unit}`,
        ...(behind ? { behind } : {}),
      };
    };

    return [
      stat("Calories", calories, targets.calories.dailyTarget, ""),
      stat("Protein", protein, targets.protein.targetG ?? 0, " g"),
      stat("Carbs", carbs, targets.carbs.targetG ?? 0, " g"),
      stat("Fat", fat, targets.fat.targetG ?? 0, " g"),
    ];
  },

  async getNotificationsSummary(): Promise<NotificationsSummary> {
    const summary = await api<NotificationSummaryDto>(
      "/api/v1/notifications/summary",
    );
    const attention: AttentionItem[] = [];
    if (summary.urgentCount > 0) {
      attention.push({
        kind: "expiry",
        text: `${summary.urgentCount} urgent notification${summary.urgentCount === 1 ? "" : "s"} need action`,
      });
    }
    if (summary.attentionCount > summary.urgentCount) {
      attention.push({
        kind: "defrost",
        text: `${summary.attentionCount - summary.urgentCount} time-sensitive item${summary.attentionCount - summary.urgentCount === 1 ? "" : "s"} today`,
      });
    }
    if (summary.unreadCount > summary.attentionCount) {
      attention.push({
        kind: "ai",
        text: `${summary.unreadCount - summary.attentionCount} update${summary.unreadCount - summary.attentionCount === 1 ? "" : "s"} waiting for review`,
      });
    }
    return { unread: summary.unreadCount, attention };
  },

  async getWeekBudget(): Promise<WeekBudget> {
    const budget = await api<BudgetDto>("/api/v1/provisions/budget");
    const money = new Intl.NumberFormat("en-GB", {
      style: "currency",
      currency: budget.currency,
    });
    const tracking = budget.spendTracking;
    const spent = tracking?.currentWeekActual ?? 0;
    const target = tracking?.currentWeekTarget ?? budget.weeklyTarget;
    const pct = target > 0 ? Math.min(100, (spent / target) * 100) : 0;
    return {
      spentDisplay: money.format(spent),
      totalDisplay: money.format(target),
      pct,
      note: tracking
        ? spent <= target
          ? "On track"
          : "Over budget"
        : "Spend tracking arrives with order history",
    };
  },

  async getTopPendingChange(): Promise<AdvisorSuggestion | null> {
    const household = await currentHousehold();
    try {
      const page = await api<ReoptSuggestionDtoPage>(
        `/api/v1/plans/suggestions?householdId=${household.id}&page=0&size=1`,
      );
      const top = page.content[0];
      if (!top) return null;
      return {
        label: "Suggestion · plan re-optimisation",
        title: top.summary,
        sub: `Trigger: ${top.triggerKind.toLowerCase().replace(/_/g, " ")} · ${top.affectedSlotIds.length} slot${top.affectedSlotIds.length === 1 ? "" : "s"} affected`,
      };
    } catch (e) {
      // No suggestions surface is non-fatal for the dashboard.
      if (e instanceof ApiError && e.status === 404) return null;
      throw e;
    }
  },
};
