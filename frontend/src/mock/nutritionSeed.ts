/**
 * Seed data for the nutrition slices — production DTO shapes throughout
 * (see design/frontend/pages/nutrition.md). The seed covers the plan week
 * Mon 8 – Sun 14 June 2026: past days fully decided, today (Wed 10) live
 * with dinner PENDING, future days pre-filled PENDING from the plan.
 *
 * Engineered demo states:
 * - Tue protein lands below the 110 g hard floor → one weekly floor
 *   violation chip ("protein floor missed · Tue").
 * - Today's snacks push calories ≥15% over the decided-so-far plan while
 *   dinner is still PENDING → the divergence advisor banner shows.
 * - Two directives: whoop TARGET_ADJUSTMENT (PASSED_WITH_WARNINGS) and a
 *   BLOCKED zoe ELIMINATION_TRIAL with phases (Accept disabled).
 * - Three ingredientCache rows with needsReview / confidence < 0.85.
 */

import type {
  ActualIntakeDto,
  DailyActivityDto,
  FoodMoodEntryDto,
  HealthDirectiveDto,
  IngredientNutritionDocument,
  IngredientNutritionDto,
  IntakeDayDto,
  IntakeSlotDto,
  IntakeSlotStatus,
  IntakeSnackDto,
  IntakeSource,
  LogSnackRequest,
  MealSlot,
  NutritionState,
  PlannedIntakeDto,
  TargetsDto,
} from "./types";

export const MOCK_USER_ID = "user-iren-0001";
const USER_ID = MOCK_USER_ID;
const PLAN_ID = "plan-2026-w24";

/** Mon-anchored plan week; index 2 is the mock's fixed "today". */
export const WEEK_DATES: string[] = [
  "2026-06-08",
  "2026-06-09",
  "2026-06-10",
  "2026-06-11",
  "2026-06-12",
  "2026-06-13",
  "2026-06-14",
];

export const TODAY_INDEX = 2;

/** The mock's fixed "today" (Wednesday 10 June 2026). */
export const MOCK_TODAY_ISO = WEEK_DATES[TODAY_INDEX];

export const WEEK_DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/* ---- builders ----------------------------------------------------------- */

/**
 * Compact micros map. Note `saturated_fat_g` rides in the micros map:
 * DailyAggregateDto carries no satFat aggregate even though TargetsDto has a
 * satFat target — the stat band's 6th cell reads this key (backend gap,
 * flagged in the page spec PR).
 */
function micros(
  iron: number,
  zinc: number,
  b12: number,
  vitD: number,
  omega3: number,
  magnesium: number,
  calcium: number,
  sodium: number,
  satFat: number,
): Record<string, number> {
  return {
    iron_mg: iron,
    zinc_mg: zinc,
    vitamin_b12_mcg: b12,
    vitamin_d_mcg: vitD,
    omega3_g: omega3,
    magnesium_mg: magnesium,
    calcium_mg: calcium,
    sodium_mg: sodium,
    saturated_fat_g: satFat,
  };
}

let slotSeq = 0;

function slot(
  mealSlot: MealSlot,
  recipeId: string | null,
  calories: number,
  proteinG: number,
  carbsG: number,
  fatG: number,
  fibreG: number,
  m: Record<string, number>,
  status: IntakeSlotStatus = "PENDING",
): IntakeSlotDto {
  const planned: PlannedIntakeDto = {
    recipeId,
    calories,
    proteinG,
    carbsG,
    fatG,
    fibreG,
    micros: m,
  };
  let actual: ActualIntakeDto;
  if (status === "PENDING") {
    actual = { status, needsAiParse: false };
  } else if (status === "SKIPPED") {
    actual = {
      status,
      calories: 0,
      proteinG: 0,
      carbsG: 0,
      fatG: 0,
      fibreG: 0,
      micros: {},
      needsAiParse: false,
    };
  } else {
    // CONFIRMED seeds credit the planned values exactly.
    actual = {
      status,
      calories,
      proteinG,
      carbsG,
      fatG,
      fibreG,
      micros: m,
      needsAiParse: false,
    };
  }
  return { id: `slot-${++slotSeq}`, mealSlot, planned, actual };
}

let snackSeq = 0;

function snack(
  freeText: string,
  ingredientMappingKey: string | null,
  quantityG: number,
  calories: number,
  proteinG: number,
  carbsG: number,
  fatG: number,
  fibreG: number,
  source: IntakeSource,
  loggedAt: string,
  m?: Record<string, number>,
): IntakeSnackDto {
  return {
    id: `snack-${++snackSeq}`,
    ingredientMappingKey,
    freeText,
    quantityG,
    calories,
    proteinG,
    carbsG,
    fatG,
    fibreG,
    micros: m ?? null,
    source,
    loggedAt,
  };
}

function day(
  onDate: string,
  slots: IntakeSlotDto[],
  snacks: IntakeSnackDto[],
): IntakeDayDto {
  return {
    id: `intake-${onDate}`,
    userId: USER_ID,
    onDate,
    planId: PLAN_ID,
    slots,
    snacks,
    version: 2,
  };
}

/* ---- intake days ---------------------------------------------------------- */

const intakeDaysSeed: Record<string, IntakeDayDto> = {
  // Mon 8 — all confirmed; protein shake keeps protein above the 110 g floor.
  "2026-06-08": day(
    "2026-06-08",
    [
      slot("BREAKFAST", null, 380, 20, 55, 10, 6, micros(3.5, 2.1, 0.6, 1.2, 0.3, 95, 210, 160, 3.0), "CONFIRMED"),
      slot("LUNCH", "chicken-stir-fry", 520, 34, 42, 16, 5, micros(2.8, 2.6, 0.5, 0.2, 0.3, 85, 65, 980, 3.4), "CONFIRMED"),
      slot("DINNER", "salmon-traybake", 560, 36, 38, 26, 6, micros(1.6, 1.2, 4.8, 11.0, 1.8, 70, 55, 420, 4.6), "CONFIRMED"),
    ],
    [
      snack("Protein shake with oat milk", null, 350, 220, 30, 12, 4, 1, "MANUAL", "2026-06-08T16:30:00Z", { calcium_mg: 240 }),
    ],
  ),
  // Tue 9 — all confirmed; low-protein day → the week's floor violation.
  "2026-06-09": day(
    "2026-06-09",
    [
      slot("BREAKFAST", null, 420, 22, 30, 24, 3, micros(2.4, 1.8, 1.1, 1.8, 0.2, 40, 120, 540, 6.5), "CONFIRMED"),
      slot("LUNCH", "chicken-stir-fry", 520, 34, 42, 16, 5, micros(2.8, 2.6, 0.5, 0.2, 0.3, 85, 65, 980, 3.4), "CONFIRMED"),
      slot("DINNER", "pasta-norma", 540, 18, 74, 19, 8, micros(2.2, 1.4, 0.3, 0.1, 0.2, 60, 140, 620, 5.2), "CONFIRMED"),
    ],
    [
      snack("Oat flapjack", "oat flapjack", 40, 170, 3, 22, 8, 2, "OPEN_FOOD_FACTS", "2026-06-09T15:10:00Z", { sodium_mg: 85, saturated_fat_g: 3.9 }),
    ],
  ),
  // Wed 10 (today) — breakfast + lunch confirmed, dinner pending (actionable).
  "2026-06-10": day(
    "2026-06-10",
    [
      slot("BREAKFAST", null, 380, 20, 55, 10, 6, micros(3.5, 2.1, 0.6, 1.2, 0.3, 95, 210, 160, 3.0), "CONFIRMED"),
      slot("LUNCH", "chicken-stir-fry", 520, 34, 42, 16, 5, micros(2.8, 2.6, 0.5, 0.2, 0.3, 85, 65, 980, 3.4), "CONFIRMED"),
      slot("DINNER", "tofu-bibimbap", 520, 28, 55, 18, 7, micros(4.2, 2.4, 0.1, 0.4, 0.6, 115, 180, 850, 2.6), "PENDING"),
    ],
    [
      snack("Morning smoothie — banana, berries, oats", null, 350, 420, 12, 60, 14, 4, "MANUAL", "2026-06-10T10:42:00Z", { calcium_mg: 320, magnesium_mg: 65, saturated_fat_g: 1.2 }),
      snack("Flat white", "flat white", 250, 90, 5, 7, 5, 0, "MANUAL", "2026-06-10T08:35:00Z", { calcium_mg: 140, saturated_fat_g: 3.0 }),
    ],
  ),
  // Thu–Sun — pre-filled PENDING from the plan, nothing logged yet.
  "2026-06-11": day(
    "2026-06-11",
    [
      slot("BREAKFAST", null, 350, 18, 36, 14, 4, micros(1.1, 1.4, 0.9, 1.0, 0.2, 45, 240, 110, 4.2)),
      slot("LUNCH", null, 480, 22, 62, 16, 9, micros(3.4, 2.2, 0.0, 0.0, 0.4, 120, 80, 480, 2.2)),
      slot("DINNER", "chicken-stir-fry", 480, 34, 42, 16, 5, micros(2.8, 2.6, 0.5, 0.2, 0.3, 85, 65, 980, 3.4)),
    ],
    [],
  ),
  "2026-06-12": day(
    "2026-06-12",
    [
      slot("BREAKFAST", null, 420, 22, 30, 24, 3, micros(2.4, 1.8, 1.1, 1.8, 0.2, 40, 120, 540, 6.5)),
      slot("LUNCH", null, 450, 28, 40, 18, 4, micros(2.0, 2.2, 0.6, 0.3, 0.2, 55, 90, 720, 4.8)),
      slot("DINNER", "fish-tacos", 470, 27, 48, 17, 6, micros(1.8, 1.5, 2.6, 4.2, 0.9, 65, 130, 580, 3.8)),
    ],
    [],
  ),
  "2026-06-13": day(
    "2026-06-13",
    [
      slot("BREAKFAST", null, 520, 14, 70, 20, 3, micros(1.9, 1.2, 0.7, 0.8, 0.2, 35, 160, 420, 7.5)),
      slot("LUNCH", null, 430, 19, 58, 14, 8, micros(3.1, 2.0, 0.1, 0.1, 0.3, 95, 70, 540, 5.4)),
      slot("DINNER", null, 850, 32, 95, 35, 6, micros(3.6, 3.2, 1.4, 0.6, 0.4, 70, 420, 1480, 12.0)),
    ],
    [],
  ),
  "2026-06-14": day(
    "2026-06-14",
    [
      slot("BREAKFAST", "shakshuka", 390, 21, 26, 23, 5, micros(2.9, 1.9, 1.2, 2.1, 0.3, 55, 150, 640, 6.0)),
      slot("LUNCH", null, 380, 12, 52, 12, 7, micros(2.4, 1.3, 0.1, 0.0, 0.2, 60, 80, 690, 2.0)),
      slot("DINNER", null, 520, 30, 50, 20, 8, micros(3.8, 2.8, 0.8, 0.4, 0.5, 100, 95, 760, 4.4)),
    ],
    [],
  ),
};

/* ---- daily activity --------------------------------------------------------- */

const dailyActivitySeed: Record<string, DailyActivityDto> = {
  "2026-06-08": {
    id: "act-2026-06-08",
    userId: USER_ID,
    onDate: "2026-06-08",
    activityLevel: "TRAINING_DAY",
    notes: "Push day + 5k row",
    createdAt: "2026-06-08T07:05:00Z",
  },
  "2026-06-09": {
    id: "act-2026-06-09",
    userId: USER_ID,
    onDate: "2026-06-09",
    activityLevel: "REST_DAY",
    notes: null,
    createdAt: "2026-06-09T07:10:00Z",
  },
  "2026-06-10": {
    id: "act-2026-06-10",
    userId: USER_ID,
    onDate: "2026-06-10",
    activityLevel: "TRAINING_DAY",
    notes: "Evening gym class",
    createdAt: "2026-06-10T07:02:00Z",
  },
};

/* ---- journal ------------------------------------------------------------------ */

const journalSeed: FoodMoodEntryDto[] = [
  {
    id: "jm-5",
    userId: USER_ID,
    onDate: "2026-06-10",
    mealSlot: "BREAKFAST",
    journalEntry:
      "Slept badly — went for the bigger breakfast and felt better for it.",
    loggedAt: "2026-06-10T09:10:00Z",
    optimisticVersion: 0,
  },
  {
    id: "jm-4",
    userId: USER_ID,
    onDate: "2026-06-09",
    mealSlot: "DINNER",
    journalEntry: "Post-gym dinner felt right, not stuffed.",
    loggedAt: "2026-06-09T20:40:00Z",
    optimisticVersion: 0,
  },
  {
    id: "jm-3",
    userId: USER_ID,
    onDate: "2026-06-08",
    mealSlot: null,
    journalEntry: "Afternoon slump around 4pm again — maybe a bigger lunch.",
    loggedAt: "2026-06-08T16:20:00Z",
    optimisticVersion: 1,
  },
  {
    id: "jm-2",
    userId: USER_ID,
    onDate: "2026-06-05",
    mealSlot: "LUNCH",
    journalEntry: "Big salad lunch left me hungry by 3 — note for repeat weeks.",
    loggedAt: "2026-06-05T15:05:00Z",
    optimisticVersion: 0,
  },
  {
    id: "jm-1",
    userId: USER_ID,
    onDate: "2026-06-03",
    mealSlot: "SNACKS",
    journalEntry: "Afternoon flapjack habit creeping back in.",
    loggedAt: "2026-06-03T16:55:00Z",
    optimisticVersion: 0,
  },
];

/* ---- targets -------------------------------------------------------------------- */

export const targetsSeed: TargetsDto = {
  id: "tgt-0001",
  userId: USER_ID,
  goal: "MAINTAIN",
  calories: {
    dailyTarget: 2000,
    toleranceUnder: 100,
    toleranceOver: 50,
    enforcement: "DAILY",
    direction: "UPPER_LIMIT",
  },
  protein: {
    targetG: 120,
    floorG: 110,
    enforcement: "DAILY",
    direction: "LOWER_FLOOR",
    isHardFloor: true,
  },
  carbs: {
    targetG: 220,
    floorG: null,
    enforcement: "WEEKLY_AVERAGE",
    direction: "UPPER_LIMIT",
    isHardFloor: false,
  },
  fat: {
    targetG: 70,
    floorG: 45,
    enforcement: "WEEKLY_AVERAGE",
    direction: "BOTH_BOUNDED",
    isHardFloor: false,
  },
  fibre: {
    targetG: 30,
    floorG: 25,
    enforcement: "DAILY",
    direction: "LOWER_FLOOR",
    isHardFloor: false,
  },
  satFat: {
    targetG: 20,
    floorG: null,
    enforcement: "DAILY",
    direction: "UPPER_LIMIT",
    isHardFloor: false,
  },
  notes: null,
  userOverriddenDirections: ["fat"],
  perMealDistribution: [
    { mealSlot: "BREAKFAST", calorieTarget: 400, proteinTargetG: 30 },
    { mealSlot: "LUNCH", calorieTarget: 600, proteinTargetG: 40 },
    { mealSlot: "DINNER", calorieTarget: 700, proteinTargetG: 40 },
    { mealSlot: "SNACKS", calorieTarget: 300, proteinTargetG: 10 },
  ],
  microTargets: [
    { nutrientKey: "iron_mg", targetValue: 18, upperLimit: null, sourcePreference: null, notes: "tracked since Feb bloods", isHardFloor: true },
    { nutrientKey: "zinc_mg", targetValue: 11, upperLimit: null, sourcePreference: null, notes: null, isHardFloor: false },
    { nutrientKey: "vitamin_b12_mcg", targetValue: 2.4, upperLimit: null, sourcePreference: "prefer food over supplements", notes: null, isHardFloor: false },
    { nutrientKey: "vitamin_d_mcg", targetValue: 15, upperLimit: null, sourcePreference: null, notes: "supplemented Oct–Mar", isHardFloor: false },
    { nutrientKey: "omega3_g", targetValue: 1.6, upperLimit: null, sourcePreference: null, notes: null, isHardFloor: false },
    { nutrientKey: "magnesium_mg", targetValue: 400, upperLimit: null, sourcePreference: null, notes: null, isHardFloor: false },
    { nutrientKey: "calcium_mg", targetValue: 1000, upperLimit: null, sourcePreference: null, notes: null, isHardFloor: false },
    { nutrientKey: "sodium_mg", targetValue: null, upperLimit: 2300, sourcePreference: null, notes: "NHS guideline ceiling", isHardFloor: false },
  ],
  eatingWindow: {
    enabled: false,
    windowStart: "08:00",
    windowEnd: "20:00",
    notes: null,
  },
  activityAdjustments: [
    { activityLevel: "REST_DAY", calorieModifier: -150, carbModifierG: -30 },
    { activityLevel: "LIGHT_ACTIVITY", calorieModifier: 0, carbModifierG: 0 },
    { activityLevel: "TRAINING_DAY", calorieModifier: 200, carbModifierG: 40 },
    { activityLevel: "HEAVY_TRAINING", calorieModifier: 400, carbModifierG: 70 },
  ],
  createdAt: "2026-05-02T09:00:00Z",
  version: 4,
};

/* ---- health directives -------------------------------------------------------------- */

const directivesSeed: HealthDirectiveDto[] = [
  {
    id: "hd-2026-0142",
    userId: USER_ID,
    externalDirectiveId: "whoop-rec-88412",
    sourcePlatform: "whoop",
    receivedAt: "2026-06-10T06:12:00Z",
    status: "PENDING_REVIEW",
    directiveType: "TARGET_ADJUSTMENT",
    evidenceSummary:
      "Recovery scores trended down 18% over three weeks and sleep debt is accumulating. Suggests trimming the training-day calorie surplus by 50 kcal and shifting carbs earlier in the day.",
    evidenceConfidence: "MODERATE",
    instruction: {
      action: "ADJUST_TARGET",
      target: "activityAdjustments.TRAINING_DAY.calorieModifier: +200 → +150",
      scope: "training_days",
      duration: { type: "FIXED_WEEKS", durationWeeks: 4 },
    },
    mapsToModel: "nutrition.targets",
    mapsToTier: null,
    temporary: true,
    autoExpiresAt: "2026-07-08T06:12:00Z",
    decidedAt: null,
    decidedByUserId: null,
    userModification: null,
    rejectionReason: null,
    safetyGateVerdict: "PASSED_WITH_WARNINGS",
    safetyGateFindings: [
      {
        code: "CALORIE_FLOOR_PROXIMITY",
        severity: "WARN",
        message:
          "Reduced training-day calories land within 6% of your minimum safe intake — keep an eye on energy levels.",
      },
    ],
    optimisticVersion: 1,
  },
  {
    id: "hd-2026-0139",
    userId: USER_ID,
    externalDirectiveId: "zoe-trial-2207",
    sourcePlatform: "zoe",
    receivedAt: "2026-06-08T18:45:00Z",
    status: "PENDING_REVIEW",
    directiveType: "ELIMINATION_TRIAL",
    evidenceSummary:
      "Repeated post-meal bloating flagged after wheat-heavy dinners (4 of 6 logged episodes). Proposes a phased gluten elimination trial.",
    evidenceConfidence: "LOW",
    instruction: {
      action: "ELIMINATE",
      target: "gluten",
      scope: "all_meals",
      duration: {
        type: "PHASED",
        phases: [
          {
            phase: "elimination",
            durationWeeks: 3,
            rule: "No gluten-containing ingredients in any slot",
          },
          {
            phase: "reintroduction",
            durationWeeks: 1,
            rule: "One serving every other day, log symptoms",
          },
          {
            phase: "observation",
            durationWeeks: 2,
            rule: "Normal diet, daily symptom journal",
          },
        ],
      },
    },
    mapsToModel: "preference.constraints",
    mapsToTier: null,
    temporary: true,
    autoExpiresAt: "2026-06-22T18:45:00Z",
    decidedAt: null,
    decidedByUserId: null,
    userModification: null,
    rejectionReason: null,
    safetyGateVerdict: "BLOCKED",
    safetyGateFindings: [
      {
        code: "FLOOR_INFEASIBLE",
        severity: "BLOCK",
        message:
          "Eliminating gluten grains alongside Maya's vegetarian constraint makes the fibre floor infeasible for the current recipe pool.",
      },
      {
        code: "SCOPE_HOUSEHOLD",
        severity: "INFO",
        message:
          "Trial would apply to shared dinner slots — other household members are affected.",
      },
    ],
    optimisticVersion: 1,
  },
];

/* ---- ingredient cache ------------------------------------------------------------------ */

function per100(
  calories: number,
  proteinG: number,
  carbsG: number,
  fatG: number,
  fibreG: number,
  saturatedFatG: number,
  sugarG: number,
  m?: Record<string, number>,
  vitamins?: Record<string, number>,
): IngredientNutritionDocument {
  return {
    calories,
    proteinG,
    carbsG,
    fatG,
    fibreG,
    saturatedFatG,
    sugarG,
    micros: m ?? {},
    vitamins: vitamins ?? {},
  };
}

const ingredientCacheSeed: IngredientNutritionDto[] = [
  {
    searchTerm: "banana",
    source: "USDA",
    externalId: "usda-1105314",
    nutritionPer100g: per100(89, 1.1, 22.8, 0.3, 2.6, 0.1, 12.2, { potassium_mg: 358, magnesium_mg: 27 }, { vitamin_c_mg: 8.7, vitamin_b6_mg: 0.4 }),
    defaultPieceGrams: 118,
    confidence: 0.99,
    needsReview: false,
    lastVerifiedAt: "2026-05-28T11:00:00Z",
    version: 3,
  },
  {
    searchTerm: "greek yoghurt",
    source: "USDA",
    externalId: "usda-1097559",
    nutritionPer100g: per100(97, 9.0, 3.9, 5.0, 0, 3.2, 3.6, { calcium_mg: 110, sodium_mg: 35 }, { vitamin_b12_mcg: 0.5 }),
    defaultPieceGrams: null,
    confidence: 0.97,
    needsReview: false,
    lastVerifiedAt: "2026-05-28T11:00:00Z",
    version: 2,
  },
  {
    searchTerm: "rolled oats",
    source: "USDA",
    externalId: "usda-1101825",
    nutritionPer100g: per100(379, 13.2, 67.7, 6.5, 10.1, 1.2, 1.0, { iron_mg: 4.3, magnesium_mg: 138 }),
    defaultPieceGrams: null,
    confidence: 0.96,
    needsReview: false,
    lastVerifiedAt: "2026-04-14T09:30:00Z",
    version: 1,
  },
  {
    searchTerm: "flat white",
    source: "MANUAL",
    externalId: null,
    nutritionPer100g: per100(36, 2.0, 2.8, 2.0, 0, 1.2, 2.8, { calcium_mg: 56 }),
    defaultPieceGrams: 250,
    confidence: 1.0,
    needsReview: false,
    lastVerifiedAt: "2026-06-01T08:00:00Z",
    version: 2,
  },
  {
    searchTerm: "almond butter",
    source: "USDA",
    externalId: "usda-1100559",
    nutritionPer100g: per100(614, 21.0, 18.8, 55.5, 10.3, 4.2, 4.4, { magnesium_mg: 279, calcium_mg: 347 }),
    defaultPieceGrams: 16,
    confidence: 0.95,
    needsReview: false,
    lastVerifiedAt: "2026-05-02T10:15:00Z",
    version: 1,
  },
  // Needs-review queue (confidence < 0.85) — Data quality tab.
  {
    searchTerm: "protein bar",
    source: "OPEN_FOOD_FACTS",
    externalId: "off-5060337500401",
    nutritionPer100g: per100(350, 33.0, 30.0, 12.0, 8.0, 5.5, 18.0, { sodium_mg: 280 }),
    defaultPieceGrams: 60,
    confidence: 0.78,
    needsReview: true,
    lastVerifiedAt: "2026-06-02T17:20:00Z",
    version: 1,
  },
  {
    searchTerm: "oat flapjack",
    source: "OPEN_FOOD_FACTS",
    externalId: "off-5018374112345",
    nutritionPer100g: per100(430, 6.2, 55.0, 20.0, 5.5, 9.8, 26.0, { sodium_mg: 210 }),
    defaultPieceGrams: 40,
    confidence: 0.62,
    needsReview: true,
    lastVerifiedAt: null,
    version: 1,
  },
  {
    searchTerm: "tahini",
    source: "OPEN_FOOD_FACTS",
    externalId: "off-5290011000232",
    nutritionPer100g: per100(595, 17.0, 21.2, 53.8, 9.3, 7.5, 0.5, { calcium_mg: 426, iron_mg: 8.9 }),
    defaultPieceGrams: 15,
    confidence: 0.81,
    needsReview: true,
    lastVerifiedAt: "2026-05-19T13:45:00Z",
    version: 1,
  },
];

/* ---- quick snacks (chips pre-fill the full add-snack form) ------------------------ */

export const QUICK_SNACKS: Array<{ label: string; req: LogSnackRequest }> = [
  {
    label: "Banana · 105 kcal",
    req: {
      freeText: "Banana",
      ingredientMappingKey: "banana",
      quantityG: 118,
      calories: 105,
      proteinG: 1.3,
      carbsG: 26.9,
      fatG: 0.4,
      fibreG: 3.1,
      micros: { potassium_mg: 422, magnesium_mg: 32 },
      source: "USDA",
      deductFromPantry: false,
    },
  },
  {
    label: "Greek yoghurt · 150 kcal",
    req: {
      freeText: "Greek yoghurt bowl",
      ingredientMappingKey: "greek yoghurt",
      quantityG: 150,
      calories: 146,
      proteinG: 13.5,
      carbsG: 5.9,
      fatG: 7.5,
      fibreG: 0,
      micros: { calcium_mg: 165, saturated_fat_g: 4.8 },
      source: "USDA",
      deductFromPantry: false,
    },
  },
  {
    label: "Protein bar · 210 kcal",
    req: {
      freeText: "Protein bar",
      ingredientMappingKey: "protein bar",
      quantityG: 60,
      calories: 210,
      proteinG: 19.8,
      carbsG: 18,
      fatG: 7.2,
      fibreG: 4.8,
      micros: { sodium_mg: 168, saturated_fat_g: 3.3 },
      source: "OPEN_FOOD_FACTS",
      deductFromPantry: false,
    },
  },
  {
    label: "Handful of nuts · 180 kcal",
    req: {
      freeText: "Handful of mixed nuts",
      ingredientMappingKey: null,
      quantityG: 30,
      calories: 180,
      proteinG: 6,
      carbsG: 5,
      fatG: 16,
      fibreG: 2.4,
      micros: { magnesium_mg: 75, saturated_fat_g: 2.1 },
      source: "MANUAL",
      deductFromPantry: false,
    },
  },
];

/* ---- root ----------------------------------------------------------------------------- */

export function createNutritionSeed(): NutritionState {
  return {
    intakeDays: intakeDaysSeed,
    parsingSlotIds: [],
    dailyActivity: dailyActivitySeed,
    journal: journalSeed,
    directives: directivesSeed,
    ingredientCache: ingredientCacheSeed,
  };
}
