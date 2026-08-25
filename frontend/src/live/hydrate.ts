/**
 * Live hydration — fills the in-memory store from the real backend on boot, so
 * every wired page renders live data through the same selectors it uses against
 * the fixtures. Each slice degrades independently: a 404 / error becomes an
 * empty slice, not a blanked app (the page spec's composite-degradation rules).
 *
 * One boot fetch covers all pages (one dev user, small data). Per-recipe detail
 * (substitutions/ratings/versions) and notification/delivery-log are lazy and
 * left empty here.
 */
import { hydrateStore } from "../mock/store";
import type {
  BudgetDto,
  ClarificationQueryDto,
  DailyActivityDto,
  DiscoveryJobDto,
  DiscoverySourceDto,
  EquipmentDto,
  FeedbackEntryDto,
  FoodMoodEntryDto,
  GroceryOrderDto,
  GroceryProviderStateDto,
  HardConstraintsAuditEntryDto,
  HardConstraintsDto,
  HealthDirectiveDto,
  HouseholdDto,
  HouseholdInviteDto,
  HouseholdSettingsAuditEntryDto,
  HouseholdSettingsDto,
  IngredientNutritionDto,
  IntakeDayDto,
  InventoryItemDto,
  LifestyleConfigAuditEntryDto,
  LifestyleConfigDto,
  LoginResponse,
  MisclassificationCorrectionDto,
  MockNotificationDto,
  NotificationDto,
  PendingChangeListItemDto,
  PlanDto,
  PreferenceArchiveEntryDto,
  RecipeDto,
  RecipeRatingDto,
  RecipeSubstitutionDto,
  RecipeVersionDto,
  ReoptSuggestionDto,
  ShoppingListDto,
  SlotConfigurationDto,
  SupplierProductDto,
  TargetsDto,
  TasteProfileAuditEntryDto,
  TasteProfileDto,
  TasteProfileVersionDto,
  WasteEntryDto,
} from "../mock/types";
import { apiGetOrNull } from "./client";
import { CURRENT_WEEK_START, MOCK_TODAY_ISO, WEEK_DATES } from "./dates";

interface Page<T> {
  content: T[];
}

export interface HydrationResult {
  hasHousehold: boolean;
  planActive: boolean;
}

/** GET that swallows ALL errors → null, so one flaky/empty endpoint never
 *  blanks the boot. Use for supplementary slices (not the auth/household gate). */
async function soft<T>(path: string): Promise<T | null> {
  try {
    return await apiGetOrNull<T>(path);
  } catch {
    return null;
  }
}

/** Normalise an array-or-Page-or-null response into a plain array. */
function asList<T>(r: T[] | Page<T> | null): T[] {
  if (!r) return [];
  return Array.isArray(r) ? r : (r.content ?? []);
}

function unionById<T extends { id: string }>(a: T[], b: T[]): T[] {
  const seen = new Set(a.map((x) => x.id));
  return [...a, ...b.filter((x) => !seen.has(x.id))];
}

/** HH:MM:SS (or HH:MM) → HH:MM, null-safe. */
function hhmm(t: string | null | undefined): string | null {
  return t ? t.slice(0, 5) : null;
}

function daysAgoIso(n: number): string {
  const d = new Date(`${MOCK_TODAY_ISO}T00:00:00`);
  d.setDate(d.getDate() - n);
  const p = (x: number) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export async function hydrateLive(): Promise<HydrationResult> {
  const [user, household] = await Promise.all([
    apiGetOrNull<LoginResponse>("/api/v1/auth/me"),
    apiGetOrNull<HouseholdDto>("/api/v1/households/current"),
  ]);

  // No household → onboarding not done → no plan. Hydrate identity only.
  if (!household) {
    hydrateStore((s) => ({
      ...s,
      session: { ...s.session, user: user ?? s.session.user },
    }));
    return { hasHousehold: false, planActive: false };
  }

  const hid = household.id;
  const plan = await apiGetOrNull<PlanDto>(
    `/api/v1/plans/active?householdId=${hid}&weekStartDate=${CURRENT_WEEK_START}`,
  );

  // Recipe-name join for the plan's slots (guarantees plan recipe names even if
  // the catalogue list is paginated).
  const planRecipeIds = new Set<string>();
  for (const day of plan?.days ?? []) {
    for (const slot of day.slots) {
      if (slot.scheduledRecipe) planRecipeIds.add(slot.scheduledRecipe.recipeId);
    }
  }

  // Fan out every page's reads. soft() so any single failure → empty slice.
  const [
    targets,
    intake,
    notifPage,
    budget,
    pending,
    suggestionPage,
    cataloguePage,
    planRecipeList,
    directives,
    journal,
    ingredientCache,
    activityList,
    tasteProfile,
    tasteVersions,
    tasteAudit,
    hardConstraints,
    hardAudit,
    lifestyle,
    lifestyleAudit,
    archive,
    settings,
    settingsAudit,
    resolved,
    invites,
    inventory,
    equipment,
    waste,
    supplierProducts,
    listCurrent,
    listHistory,
    orders,
    providerState,
    feedback,
    clarifications,
    corrections,
    sources,
    jobs,
  ] = await Promise.all([
    soft<TargetsDto>("/api/v1/nutrition/targets"),
    soft<IntakeDayDto>(`/api/v1/nutrition/intake/${MOCK_TODAY_ISO}`),
    soft<Page<NotificationDto>>("/api/v1/notifications?size=50"),
    soft<BudgetDto>("/api/v1/provisions/budget"),
    soft<PendingChangeListItemDto[]>("/api/v1/adaptation/pending-changes"),
    soft<Page<ReoptSuggestionDto>>(
      `/api/v1/plans/suggestions?householdId=${hid}&page=0&size=20`,
    ),
    soft<Page<RecipeDto>>("/api/v1/recipes?page=0&size=200"),
    Promise.all(
      [...planRecipeIds].map((id) =>
        soft<RecipeDto>(`/api/v1/recipes/${id}`),
      ),
    ).then((rs) => rs.filter((r): r is RecipeDto => r != null)),
    soft<HealthDirectiveDto[]>("/api/v1/nutrition/health-directives"),
    soft<FoodMoodEntryDto[]>("/api/v1/nutrition/journal"),
    soft<IngredientNutritionDto[]>("/api/v1/nutrition/ingredients/needs-review"),
    soft<DailyActivityDto[]>(
      `/api/v1/nutrition/targets/activity?from=${CURRENT_WEEK_START}&to=${WEEK_DATES[6]}`,
    ),
    soft<TasteProfileDto>("/api/v1/preferences/taste-profile"),
    soft<TasteProfileVersionDto[]>("/api/v1/preferences/taste-profile/versions"),
    soft<TasteProfileAuditEntryDto[]>(
      "/api/v1/preferences/taste-profile/audit-log",
    ),
    soft<HardConstraintsDto>("/api/v1/preferences/hard-constraints"),
    soft<HardConstraintsAuditEntryDto[]>(
      "/api/v1/preferences/hard-constraints/audit-log",
    ),
    soft<LifestyleConfigDto>("/api/v1/preferences/lifestyle-config"),
    soft<LifestyleConfigAuditEntryDto[]>(
      "/api/v1/preferences/lifestyle-config/audit-log",
    ),
    soft<PreferenceArchiveEntryDto[]>("/api/v1/preferences/archive"),
    soft<HouseholdSettingsDto>(`/api/v1/households/${hid}/settings`),
    soft<HouseholdSettingsAuditEntryDto[]>(
      `/api/v1/households/${hid}/settings/audit-log`,
    ),
    soft<SlotConfigurationDto>(
      "/api/v1/households/current/slot-configuration/planner-view",
    ),
    soft<HouseholdInviteDto[]>("/api/v1/households/current/invites"),
    soft<InventoryItemDto[]>("/api/v1/provisions/inventory"),
    soft<EquipmentDto[]>("/api/v1/provisions/equipment"),
    soft<WasteEntryDto[]>(
      `/api/v1/provisions/waste?from=${daysAgoIso(90)}&to=${MOCK_TODAY_ISO}`,
    ),
    soft<SupplierProductDto[]>("/api/v1/provisions/supplier-products"),
    plan
      ? soft<ShoppingListDto>(
          `/api/v1/grocery/shopping-lists/current?planId=${plan.id}`,
        )
      : Promise.resolve(null),
    soft<Page<ShoppingListDto>>("/api/v1/grocery/shopping-lists/history"),
    soft<Page<GroceryOrderDto>>("/api/v1/grocery/orders"),
    soft<GroceryProviderStateDto>("/api/v1/grocery/orders/providers/tesco"),
    soft<Page<FeedbackEntryDto>>("/api/v1/feedback?page=0&size=30"),
    soft<ClarificationQueryDto[]>("/api/v1/feedback/clarifications"),
    soft<MisclassificationCorrectionDto[]>("/api/v1/feedback/corrections"),
    soft<DiscoverySourceDto[]>("/api/v1/discovery/sources"),
    soft<Page<DiscoveryJobDto>>("/api/v1/discovery/jobs?page=0&size=20"),
  ]);

  // Surface the resolved serve-time (effectiveMealTime, #258) in the field the
  // pages read, normalised to HH:MM.
  const planForStore: PlanDto | null = plan
    ? {
        ...plan,
        days: plan.days.map((d) => ({
          ...d,
          slots: d.slots.map((sl) => ({
            ...sl,
            mealTime: hhmm(sl.effectiveMealTime ?? sl.mealTime),
          })),
        })),
      }
    : null;

  const recipes = unionById(asList(cataloguePage), planRecipeList);
  const rows = asList(notifPage) as unknown as MockNotificationDto[];
  const dailyActivity: Record<string, DailyActivityDto> = {};
  for (const a of asList(activityList)) {
    if (a?.onDate) dailyActivity[a.onDate] = a;
  }
  const lists = listCurrent
    ? [listCurrent, ...asList(listHistory).filter((l) => l.id !== listCurrent.id)]
    : asList(listHistory);

  hydrateStore((s) => ({
    ...s,
    session: { ...s.session, user: user ?? s.session.user },
    household: {
      ...s.household,
      current: household,
      settings: settings ?? null,
      settingsAudit: asList(settingsAudit),
      resolved: resolved ?? null,
      invites: asList(invites),
      inviteCodes: {},
    },
    planner: {
      ...s.planner,
      plans: planForStore ? [planForStore] : [],
      suggestions: asList(suggestionPage),
      proposedBySuggestion: {},
      feasibility: {},
      lastReoptOutcome: null,
      racedSlot: null,
      generation: {
        status: "idle",
        weekStartDate: CURRENT_WEEK_START,
        forceRegenerateIfActive: false,
        idempotencyKey: null,
        served: {},
        resultPlanId: null,
        replayed: false,
        round: 0,
      },
    },
    recipes,
    recipeData: {
      versions: {},
      substitutions: {},
      ratings: {},
      provenance: {},
      nutritionByVersion: {},
    },
    targets: targets ?? s.targets,
    nutrition: {
      ...s.nutrition,
      intakeDays: intake ? { [MOCK_TODAY_ISO]: intake } : {},
      parsingSlotIds: [],
      dailyActivity,
      journal: asList(journal),
      directives: asList(directives),
      ingredientCache: asList(ingredientCache),
    },
    preferences: {
      ...s.preferences,
      tasteProfile: tasteProfile ?? null,
      versions: asList(tasteVersions),
      tasteAudit: asList(tasteAudit),
      refreshing: false,
      hardConstraints: hardConstraints ?? null,
      hardAudit: asList(hardAudit),
      lifestyle: lifestyle ?? null,
      lifestyleAudit: asList(lifestyleAudit),
      archive: asList(archive),
    },
    pantry: {
      ...s.pantry,
      items: asList(inventory),
      auditByItem: {},
      waste: asList(waste),
      equipment: asList(equipment),
      budget: budget ?? null,
      supplierProducts: asList(supplierProducts),
    },
    grocery: {
      ...s.grocery,
      lists,
      orders: asList(orders),
      proposalsByOrder: {},
      providerState: providerState ?? null,
      aggregates: {},
      observations: [],
    },
    notifications: { ...s.notifications, rows, prefs: null, deliveryLog: {} },
    activity: {
      ...s.activity,
      feedback: asList(feedback),
      clarifications: asList(clarifications),
      corrections: asList(corrections),
      composePrefill: null,
    },
    discovery: {
      ...s.discovery,
      jobs: asList(jobs),
      scrapeLog: {},
      sources: asList(sources),
      openJobId: null,
      skippedRowIds: [],
      cancelRequested: null,
    },
    adaptation: {
      ...s.adaptation,
      pendingChanges: pending ?? [],
      detailById: {},
      historyByRecipe: {},
    },
  }));

  return { hasHousehold: true, planActive: plan?.status === "ACTIVE" };
}

/**
 * Lazy per-recipe hydration for the recipe-detail route — the boot fetch leaves
 * `recipeData.{versions,substitutions,ratings}` empty (can't pre-fetch every
 * recipe's history), so the detail page calls this on mount/:id-change. Each
 * slice degrades to empty independently; other recipes' cached detail is kept.
 */
export async function hydrateRecipeDetail(recipeId: string): Promise<void> {
  const recipe = await soft<RecipeDto>(`/api/v1/recipes/${recipeId}`);
  if (!recipe) return; // unknown id → leave slices empty, page shows "not found"
  const branchId = recipe.currentBranchId;

  // Versions are per-branch (branchId required); hydrate the current branch.
  const [versionsPage, activeSubs] = await Promise.all([
    branchId
      ? soft<Page<RecipeVersionDto>>(
          `/api/v1/recipes/${recipeId}/versions?branchId=${branchId}&page=0&size=100`,
        )
      : Promise.resolve(null),
    soft<RecipeSubstitutionDto[]>(
      `/api/v1/recipes/${recipeId}/substitutions/active`,
    ),
  ]);

  // Group versions by branch, each branch sorted ascending so the page's
  // `branchVersions[length - 1]` is the head.
  const versionList = asList(versionsPage);
  const byBranch: Record<string, RecipeVersionDto[]> = {};
  for (const v of versionList) (byBranch[v.branchId] ??= []).push(v);
  for (const b of Object.keys(byBranch))
    byBranch[b].sort((a, c) => a.versionNumber - c.versionNumber);

  // Ratings are per-version (versionId required); fetch the head version's list
  // plus the caller's own (the list may omit it) and union for `myRatingFor`.
  const head = versionList.reduce<RecipeVersionDto | null>(
    (a, c) => (!a || c.versionNumber > a.versionNumber ? c : a),
    null,
  );
  const [ratingsPage, mine] = head
    ? await Promise.all([
        soft<Page<RecipeRatingDto>>(
          `/api/v1/recipes/${recipeId}/ratings?versionId=${head.id}&page=0&size=100`,
        ),
        soft<RecipeRatingDto>(
          `/api/v1/recipes/${recipeId}/ratings/mine?versionId=${head.id}`,
        ),
      ])
    : [null, null];
  const ratings = asList(ratingsPage);
  const allRatings = mine ? unionById(ratings, [mine]) : ratings;

  hydrateStore((s) => ({
    ...s,
    recipes: recipe
      ? s.recipes.some((r) => r.id === recipeId)
        ? s.recipes.map((r) => (r.id === recipeId ? recipe : r))
        : [...s.recipes, recipe]
      : s.recipes,
    recipeData: {
      ...s.recipeData,
      versions: { ...s.recipeData.versions, [recipeId]: byBranch },
      substitutions: {
        ...s.recipeData.substitutions,
        [recipeId]: asList(activeSubs),
      },
      ratings: { ...s.recipeData.ratings, [recipeId]: allRatings },
    },
  }));
}
