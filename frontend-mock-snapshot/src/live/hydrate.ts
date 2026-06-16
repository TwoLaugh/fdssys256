/**
 * Live hydration for the Today page.
 *
 * Fetches the real DTOs Today reads and writes them into the matching store
 * slices, so `Today.tsx` renders live data through the exact same selectors it
 * uses against the fixtures. Each card degrades independently: a 404 (no
 * targets / no budget / no intake day) becomes an empty slice, not an error,
 * mirroring the page spec's composite-degradation rules (today.md §4).
 */
import { hydrateStore } from "../mock/store";
import type {
  BudgetDto,
  HouseholdDto,
  IntakeDayDto,
  LoginResponse,
  MockNotificationDto,
  NotificationDto,
  PendingChangeListItemDto,
  PlanDto,
  RecipeDto,
  TargetsDto,
} from "../mock/types";
import { apiGetOrNull } from "./client";
import { CURRENT_WEEK_START, MOCK_TODAY_ISO } from "./dates";

interface Page<T> {
  content: T[];
}

export interface HydrationResult {
  hasHousehold: boolean;
  planActive: boolean;
}

/** HH:MM:SS (or HH:MM) → HH:MM, null-safe. */
function hhmm(t: string | null | undefined): string | null {
  return t ? t.slice(0, 5) : null;
}

export async function hydrateToday(): Promise<HydrationResult> {
  const [user, household] = await Promise.all([
    apiGetOrNull<LoginResponse>("/api/v1/auth/me"),
    apiGetOrNull<HouseholdDto>("/api/v1/households/current"),
  ]);

  // No household → onboarding not done → no plan. Hydrate identity only; Today
  // shows its no-plan CTA.
  if (!household) {
    hydrateStore((s) => ({
      ...s,
      session: { ...s.session, user: user ?? s.session.user },
    }));
    return { hasHousehold: false, planActive: false };
  }

  const plan = await apiGetOrNull<PlanDto>(
    `/api/v1/plans/active?householdId=${household.id}&weekStartDate=${CURRENT_WEEK_START}`,
  );

  // Recipe-name join: fetch every recipe the plan's slots reference.
  const recipeIds = new Set<string>();
  for (const day of plan?.days ?? []) {
    for (const slot of day.slots) {
      if (slot.scheduledRecipe) recipeIds.add(slot.scheduledRecipe.recipeId);
    }
  }
  const recipes = (
    await Promise.all(
      [...recipeIds].map((id) => apiGetOrNull<RecipeDto>(`/api/v1/recipes/${id}`)),
    )
  ).filter((r): r is RecipeDto => r != null);

  const [targets, intake, notifPage, budget, pending] = await Promise.all([
    apiGetOrNull<TargetsDto>("/api/v1/nutrition/targets"),
    apiGetOrNull<IntakeDayDto>(`/api/v1/nutrition/intake/${MOCK_TODAY_ISO}`),
    apiGetOrNull<Page<NotificationDto>>(
      "/api/v1/notifications?status=UNREAD&size=3",
    ),
    apiGetOrNull<BudgetDto>("/api/v1/provisions/budget"),
    apiGetOrNull<PendingChangeListItemDto[]>("/api/v1/adaptation/pending-changes"),
  ]);

  // Surface the resolved serve-time (effectiveMealTime, #258) in the field
  // Today reads, normalised to HH:MM.
  const planForStore: PlanDto | null = plan
    ? {
        ...plan,
        days: plan.days.map((d) => ({
          ...d,
          slots: d.slots.map((sl) => {
            // Surface the resolved serve-time (effectiveMealTime, backend #258).
            // The page reads slot.mealTime, so write the resolved time there.
            return { ...sl, mealTime: hhmm(sl.effectiveMealTime ?? sl.mealTime) };
          }),
        })),
      }
    : null;

  const rows = (notifPage?.content ?? []) as unknown as MockNotificationDto[];

  hydrateStore((s) => ({
    ...s,
    session: { ...s.session, user: user ?? s.session.user },
    household: { ...s.household, current: household },
    planner: { ...s.planner, plans: planForStore ? [planForStore] : [] },
    recipes,
    targets: targets ?? s.targets,
    nutrition: {
      ...s.nutrition,
      intakeDays: intake ? { [MOCK_TODAY_ISO]: intake } : {},
    },
    notifications: { ...s.notifications, rows },
    pantry: { ...s.pantry, budget: budget ?? null },
    adaptation: { ...s.adaptation, pendingChanges: pending ?? [] },
  }));

  return { hasHousehold: true, planActive: plan?.status === "ACTIVE" };
}
