/**
 * Recipe + discovery + adaptation seeds on the PRODUCTION DTO shapes
 * (design/frontend/pages/recipes.md, recipe-detail.md, discover.md).
 *
 * Seeded design-review states:
 * - 3-version main + one diverged branch on tofu-bibimbap (diffable v1→v3,
 *   cross-branch start row with hidden diff expander).
 * - Substitutions: ACCEPTED tamari swap at applicationCount 3 (promotion
 *   nudge) + PROPOSED swap on tofu-bibimbap; REJECTED swap (re-accept legal)
 *   on chicken-stir-fry.
 * - Adaptation: one PENDING change (detail pair + per-recipe history).
 * - Library states: SYSTEM pool rows (promote demo), archived row, fresh
 *   import with nutritionStatus PENDING, PARTIAL with needs-review rows,
 *   forked-from caption, an unrated recipe.
 * - Discovery: one PARTIAL job with a mixed-outcome scrape log (the results
 *   triage), one SCHEDULED sweep, one cancelled-but-harvest-kept FAILED job;
 *   read-only source registry with a circuit-broken and an admin-disabled row.
 * - Dedup demo: importing the seeded URL overlaps chicken-stir-fry ≥80%.
 */

import { MOCK_TODAY_ISO, MOCK_USER_ID } from "./nutritionSeed";
import {
  computeRatingSummary,
  ingredientsFromRequest,
  mainBranchId,
  ratingAggregate,
  stepsFromRequest,
} from "./recipeLogic";
import type {
  AdaptationState,
  Catalogue,
  CreateRecipeRequest,
  DataQuality,
  DiscoveryJobDto,
  DiscoveryScrapeLogEntryDto,
  DiscoverySourceDto,
  DiscoveryState,
  IngredientDto,
  NutritionStatus,
  PendingChangeDto,
  RecipeBranchDto,
  RecipeDataState,
  RecipeDto,
  RecipeImportDto,
  RecipeRatingDto,
  RecipeSubstitutionDto,
  RecipeTagsDto,
  RecipeVersionDto,
  RobotsTxtOutcome,
  ScrapeOutcome,
  ScrapeSkipReason,
  VersionTrigger,
} from "./types";

export const SELF_ACTOR = `user:${MOCK_USER_ID}`;

/* ---- photography ----------------------------------------------------------
 * A small pool of food photos reused across cards; every <img> falls back to
 * the warm #e8dcc8 swatch on error (design-language: photography section).
 */
export const IMG_BOWL =
  "https://images.unsplash.com/photo-1553163147-622ab57be1c7?w=900&q=60";
export const IMG_PLATE =
  "https://images.unsplash.com/photo-1512058564366-18510be2db19?w=900&q=60";
export const IMG_SALMON =
  "https://images.unsplash.com/photo-1467003909585-2f8a72700288?w=900&q=60";
export const IMG_TACOS =
  "https://images.unsplash.com/photo-1565299585323-38d6b0865b47?w=900&q=60";

/* ---- compact spec → DTO builder --------------------------------------------------- */

interface IngSpec {
  /** ingredientMappingKey (normalised). */
  k: string;
  /** displayName. */
  n: string;
  q?: number | null;
  u?: string | null;
  prep?: string | null;
  opt?: boolean;
  /** When set, the row is flagged needsReview with this mapping confidence. */
  review?: number;
}

interface StepSpec {
  t: string;
  m?: number;
}

export interface RecipeSpec {
  id: string;
  name: string;
  desc: string;
  catalogue: Catalogue;
  quality: DataQuality;
  nutrition: NutritionStatus;
  img: string | null;
  cuisine: string;
  prep: number;
  cook: number;
  servings: number;
  mealTypes?: string[];
  equipment?: string[];
  fridgeDays?: number;
  freezerWeeks?: number;
  packable?: boolean;
  tags?: RecipeTagsDto | null;
  ing: IngSpec[];
  steps: StepSpec[];
  createdAt: string;
  archivedAt?: string | null;
  forkedFrom?: string | null;
  trigger?: VersionTrigger;
  actor?: string;
}

function buildIngredients(specs: IngSpec[]): IngredientDto[] {
  const rows = ingredientsFromRequest(
    specs.map((s, i) => ({
      lineOrder: i,
      ingredientMappingKey: s.k,
      displayName: s.n,
      quantity: s.q ?? null,
      unit: s.u ?? null,
      preparation: s.prep ?? null,
      optional: s.opt ?? false,
    })),
  );
  return rows.map((row, i) => {
    const review = specs[i].review;
    return review != null
      ? { ...row, needsReview: true, mappingConfidence: review }
      : row;
  });
}

export function makeVersion(args: {
  recipeId: string;
  branchId: string;
  n: number;
  trigger: VersionTrigger;
  changeReason?: string | null;
  actor?: string;
  createdAt: string;
  parentVersionId?: string | null;
  ing: IngSpec[];
  steps: StepSpec[];
  metadata: NonNullable<RecipeVersionDto["metadata"]>;
  tags?: RecipeTagsDto | null;
  idOverride?: string;
}): RecipeVersionDto {
  return {
    id: args.idOverride ?? `${args.recipeId}-v${args.n}`,
    branchId: args.branchId,
    versionNumber: args.n,
    parentVersionId: args.parentVersionId ?? null,
    trigger: args.trigger,
    changeReason: args.changeReason ?? null,
    embeddingStatus: "READY",
    createdAt: args.createdAt,
    createdByActor: args.actor ?? SELF_ACTOR,
    adapterTraceId: null,
    ingredients: buildIngredients(args.ing),
    methodSteps: stepsFromRequest(
      args.steps.map((s, i) => ({
        stepNumber: i + 1,
        instruction: s.t,
        durationMinutes: s.m ?? null,
      })),
    ),
    metadata: args.metadata,
    tags: args.tags ?? null,
    appliedSubstitutionIds: null,
  };
}

export interface BuiltRecipe {
  dto: RecipeDto;
  versions: Record<string, RecipeVersionDto[]>;
}

export function buildRecipe(spec: RecipeSpec): BuiltRecipe {
  const branchId = mainBranchId(spec.id);
  const metadata: NonNullable<RecipeVersionDto["metadata"]> = {
    servings: spec.servings,
    prepTimeMins: spec.prep,
    cookTimeMins: spec.cook,
    totalTimeMins: spec.prep + spec.cook,
    equipmentRequired: spec.equipment ?? [],
    fridgeDays: spec.fridgeDays ?? null,
    freezerWeeks: spec.freezerWeeks ?? null,
    packable: spec.packable ?? false,
    cuisine: spec.cuisine,
    mealTypes: spec.mealTypes ?? ["DINNER"],
  };
  const trigger =
    spec.trigger ??
    (spec.quality === "USER_VERIFIED"
      ? "MANUAL_CREATE"
      : "IMPORT"); /* imported / discovered / AI rows all land via IMPORT */
  const v1 = makeVersion({
    recipeId: spec.id,
    branchId,
    n: 1,
    trigger,
    actor: spec.actor,
    createdAt: spec.createdAt,
    ing: spec.ing,
    steps: spec.steps,
    metadata,
    tags: spec.tags ?? null,
  });
  const branch: RecipeBranchDto = {
    id: branchId,
    recipeId: spec.id,
    parentBranchId: null,
    branchPointVersionId: null,
    name: "main",
    label: null,
    reason: null,
    currentVersion: 1,
    divergenceScore: 0,
    createdAt: spec.createdAt,
    createdByActor: spec.actor ?? SELF_ACTOR,
    adapterTraceId: null,
    version: 1,
  };
  const dto: RecipeDto = {
    id: spec.id,
    userId: MOCK_USER_ID,
    catalogue: spec.catalogue,
    name: spec.name,
    description: spec.desc,
    currentVersion: 1,
    currentBranchId: branchId,
    dataQuality: spec.quality,
    nutritionStatus: spec.nutrition,
    forkedFromRecipeId: spec.forkedFrom ?? null,
    lastUsedInPlanAt: null,
    archivedAt: spec.archivedAt ?? null,
    deletedAt: null,
    imageUrl: spec.img,
    optimisticVersion: 1,
    createdAt: spec.createdAt,
    updatedAt: spec.createdAt,
    currentVersionBody: v1,
    branches: [branch],
  };
  return { dto, versions: { [branchId]: [v1] } };
}

/* ---- the 12-recipe catalogue + library-state extras -------------------------------- */

const T = (d: string, hm = "12:00"): string => `${d}T${hm}:00Z`;

const baseSpecs: RecipeSpec[] = [
  {
    id: "chicken-stir-fry",
    name: "Chicken stir-fry",
    desc: "Velveted chicken with crisp vegetables and noodles — the household batch-cook favourite.",
    catalogue: "USER",
    quality: "USER_VERIFIED",
    nutrition: "CALCULATED",
    img: IMG_PLATE,
    cuisine: "Chinese",
    prep: 10,
    cook: 10,
    servings: 4,
    equipment: ["wok", "hob"],
    fridgeDays: 3,
    freezerWeeks: 8,
    packable: true,
    tags: {
      protein: "chicken",
      cookingMethod: "stir-fry",
      complexity: "MODERATE",
      flavourProfile: ["savoury", "ginger"],
      dietaryFlags: [],
    },
    ing: [
      { k: "chicken breast", n: "Chicken breast", q: 500, u: "g", prep: "sliced thin" },
      { k: "egg noodle", n: "Egg noodles", q: 300, u: "g" },
      { k: "soy sauce", n: "Soy sauce", q: 3, u: "tbsp" },
      { k: "broccoli", n: "Broccoli", q: 1, u: "head" },
      { k: "ginger", n: "Ginger", q: 20, u: "g", prep: "grated" },
    ],
    steps: [
      { t: "Slice the chicken thinly and velvet in cornflour and a little soy.", m: 10 },
      { t: "Stir-fry the chicken hard in two batches; set aside.", m: 5 },
      { t: "Fry the vegetables, return the chicken, add sauce and noodles.", m: 5 },
    ],
    createdAt: T("2026-04-02"),
  },
  {
    id: "salmon-traybake",
    name: "Salmon traybake",
    desc: "One-tray salmon with new potatoes and tenderstem, finished with lemon.",
    catalogue: "USER",
    quality: "IMPORTED",
    nutrition: "CALCULATED",
    img: IMG_SALMON,
    cuisine: "British",
    prep: 10,
    cook: 25,
    servings: 2,
    equipment: ["oven"],
    fridgeDays: 2,
    tags: {
      protein: "salmon",
      cookingMethod: "traybake",
      complexity: "MINIMAL",
      flavourProfile: ["citrus"],
      dietaryFlags: ["pescatarian"],
    },
    ing: [
      { k: "salmon fillet", n: "Salmon fillets", q: 2, u: "fillets" },
      { k: "new potato", n: "New potatoes", q: 400, u: "g" },
      { k: "tenderstem broccoli", n: "Tenderstem broccoli", q: 200, u: "g" },
      { k: "lemon", n: "Lemon", q: 1, u: "whole" },
      { k: "olive oil", n: "Olive oil", q: 2, u: "tbsp" },
    ],
    steps: [
      { t: "Roast the potatoes with oil for 20 minutes at 200°C.", m: 20 },
      { t: "Add the broccoli and salmon, season, and roast 12 minutes more.", m: 12 },
      { t: "Finish with lemon zest and a squeeze of juice." },
    ],
    createdAt: T("2026-05-04"),
  },
  {
    id: "pasta-norma",
    name: "Pasta alla norma",
    desc: "Fried aubergine folded through rich tomato rigatoni with ricotta salata.",
    catalogue: "USER",
    quality: "IMPORTED",
    nutrition: "PARTIAL",
    img: IMG_PLATE,
    cuisine: "Italian",
    prep: 10,
    cook: 20,
    servings: 4,
    fridgeDays: 3,
    packable: true,
    tags: {
      protein: null,
      cookingMethod: "simmer",
      complexity: "MODERATE",
      flavourProfile: ["tomato", "basil"],
      dietaryFlags: ["vegetarian"],
    },
    ing: [
      { k: "rigatoni", n: "Rigatoni", q: 400, u: "g" },
      { k: "aubergine", n: "Aubergines", q: 2, u: "whole", prep: "cubed" },
      { k: "tomato passata", n: "Tomato passata", q: 500, u: "g", review: 0.55 },
      { k: "ricotta salata", n: "Ricotta salata", q: 80, u: "g", review: 0.62 },
      { k: "basil", n: "Basil", q: 1, u: "bunch" },
    ],
    steps: [
      { t: "Salt the aubergine cubes, then fry until deeply golden.", m: 12 },
      { t: "Simmer the passata with garlic; fold in the aubergine.", m: 8 },
      { t: "Toss with pasta and finish with ricotta salata and basil." },
    ],
    createdAt: T("2026-05-12"),
  },
  {
    id: "chickpea-spinach-curry",
    name: "Chickpea & spinach curry",
    desc: "Pantry-first coconut curry the planner generated for a gap night.",
    catalogue: "USER",
    quality: "AI_GENERATED",
    nutrition: "CALCULATED",
    img: IMG_BOWL,
    cuisine: "Indian",
    prep: 5,
    cook: 20,
    servings: 4,
    fridgeDays: 4,
    freezerWeeks: 12,
    packable: true,
    actor: "ai_generation",
    tags: {
      protein: "chickpea",
      cookingMethod: "one-pot",
      complexity: "MINIMAL",
      flavourProfile: ["spiced", "coconut"],
      dietaryFlags: ["vegetarian", "vegan"],
    },
    ing: [
      { k: "chickpea", n: "Chickpeas", q: 2, u: "tins" },
      { k: "spinach", n: "Spinach", q: 200, u: "g" },
      { k: "coconut milk", n: "Coconut milk", q: 400, u: "ml" },
      { k: "curry paste", n: "Curry paste", q: 3, u: "tbsp" },
      { k: "basmati rice", n: "Basmati rice", q: 300, u: "g" },
    ],
    steps: [
      { t: "Fry the curry paste until fragrant, then add the chickpeas.", m: 4 },
      { t: "Pour in the coconut milk and simmer for 10 minutes.", m: 10 },
      { t: "Wilt in the spinach and serve over rice.", m: 4 },
    ],
    createdAt: T("2026-05-20"),
  },
  {
    id: "fish-tacos",
    name: "Fish tacos",
    desc: "Spiced pan-fried white fish with lime slaw on warm corn tortillas.",
    catalogue: "USER",
    quality: "IMPORTED",
    nutrition: "CALCULATED",
    img: IMG_TACOS,
    cuisine: "Mexican",
    prep: 10,
    cook: 15,
    servings: 4,
    tags: {
      protein: "white fish",
      cookingMethod: "pan-fry",
      complexity: "MODERATE",
      flavourProfile: ["lime", "smoky"],
      dietaryFlags: ["pescatarian"],
    },
    ing: [
      { k: "white fish fillet", n: "White fish fillets", q: 500, u: "g" },
      { k: "corn tortilla", n: "Corn tortillas", q: 12, u: "whole" },
      { k: "red cabbage", n: "Red cabbage", q: 0.25, u: "head", prep: "shredded" },
      { k: "lime", n: "Lime", q: 2, u: "whole" },
      { k: "soured cream", n: "Soured cream", q: 150, u: "ml" },
    ],
    steps: [
      { t: "Dust the fish in spiced flour and pan-fry until just done.", m: 8 },
      { t: "Shred the cabbage and dress with lime and a pinch of salt." },
      { t: "Warm the tortillas and assemble with cream and hot sauce." },
    ],
    createdAt: T("2026-05-22"),
  },
  {
    id: "tuna-melt",
    name: "Tuna melt",
    desc: "Grilled open sandwich — tinned tuna, mature cheddar, spring onion.",
    catalogue: "USER",
    quality: "USER_VERIFIED",
    nutrition: "CALCULATED",
    img: IMG_PLATE,
    cuisine: "American",
    prep: 10,
    cook: 5,
    servings: 2,
    mealTypes: ["LUNCH"],
    tags: {
      protein: "tuna",
      cookingMethod: "grill",
      complexity: "MINIMAL",
      flavourProfile: ["cheesy"],
      dietaryFlags: ["pescatarian"],
    },
    ing: [
      { k: "tuna", n: "Tuna (tinned)", q: 2, u: "tins" },
      { k: "sourdough", n: "Sourdough", q: 4, u: "slices" },
      { k: "mature cheddar", n: "Mature cheddar", q: 100, u: "g" },
      { k: "spring onion", n: "Spring onions", q: 3, u: "whole" },
      { k: "mayonnaise", n: "Mayonnaise", q: 3, u: "tbsp" },
    ],
    steps: [
      { t: "Mix the tuna with mayo, spring onion and black pepper." },
      { t: "Pile onto the bread, top with cheddar." },
      { t: "Grill until bubbling and golden.", m: 5 },
    ],
    createdAt: T("2026-03-15"),
  },
  {
    id: "shakshuka",
    name: "Shakshuka",
    desc: "Eggs baked in a cumin-spiked pepper and tomato sauce, feta to finish.",
    catalogue: "SYSTEM",
    quality: "WEB_DISCOVERED",
    nutrition: "CALCULATED",
    img: IMG_BOWL,
    cuisine: "Middle Eastern",
    prep: 10,
    cook: 20,
    servings: 4,
    mealTypes: ["BREAKFAST", "DINNER"],
    actor: "discovery_pipeline",
    tags: {
      protein: "egg",
      cookingMethod: "one-pan",
      complexity: "MODERATE",
      flavourProfile: ["spiced", "tomato"],
      dietaryFlags: ["vegetarian"],
    },
    ing: [
      { k: "egg", n: "Eggs", q: 6, u: "whole" },
      { k: "tomato passata", n: "Tomato passata", q: 500, u: "g" },
      { k: "red pepper", n: "Red peppers", q: 2, u: "whole" },
      { k: "cumin", n: "Cumin", q: 2, u: "tsp" },
      { k: "feta", n: "Feta", q: 80, u: "g", opt: true },
    ],
    steps: [
      { t: "Soften the peppers with onion and cumin.", m: 8 },
      { t: "Add the passata and reduce to a thick sauce.", m: 8 },
      { t: "Crack in the eggs, cover, and cook until just set.", m: 6 },
    ],
    createdAt: T("2026-06-02"),
  },
  {
    id: "miso-salmon-traybake",
    name: "Miso salmon traybake",
    desc: "Miso-glazed salmon with sweet potato wedges and pak choi.",
    catalogue: "SYSTEM",
    quality: "WEB_DISCOVERED",
    nutrition: "CALCULATED",
    img: IMG_SALMON,
    cuisine: "Japanese",
    prep: 10,
    cook: 20,
    servings: 4,
    equipment: ["oven"],
    actor: "discovery_pipeline",
    tags: {
      protein: "salmon",
      cookingMethod: "traybake",
      complexity: "MINIMAL",
      flavourProfile: ["umami"],
      dietaryFlags: ["pescatarian"],
    },
    ing: [
      { k: "salmon fillet", n: "Salmon fillets", q: 4, u: "fillets" },
      { k: "white miso", n: "White miso", q: 2, u: "tbsp" },
      { k: "sweet potato", n: "Sweet potatoes", q: 500, u: "g", prep: "wedges" },
      { k: "pak choi", n: "Pak choi", q: 2, u: "heads" },
      { k: "sesame seed", n: "Sesame seeds", q: 1, u: "tbsp", opt: true },
    ],
    steps: [
      { t: "Roast the sweet potato wedges for 20 minutes.", m: 20 },
      { t: "Brush the salmon with miso glaze; add with the pak choi." },
      { t: "Roast 12 minutes more and scatter with sesame.", m: 12 },
    ],
    createdAt: T("2026-06-02"),
  },
  {
    id: "black-bean-tacos",
    name: "Black bean tacos",
    desc: "Smoky mashed black beans with smashed avocado and pickled onion.",
    catalogue: "USER",
    quality: "AI_GENERATED",
    nutrition: "CALCULATED",
    img: IMG_TACOS,
    cuisine: "Mexican",
    prep: 10,
    cook: 10,
    servings: 4,
    actor: "ai_generation",
    tags: {
      protein: "black bean",
      cookingMethod: "assemble",
      complexity: "MINIMAL",
      flavourProfile: ["smoky"],
      dietaryFlags: ["vegetarian", "vegan"],
    },
    ing: [
      { k: "black bean", n: "Black beans", q: 2, u: "tins" },
      { k: "corn tortilla", n: "Corn tortillas", q: 12, u: "whole" },
      { k: "avocado", n: "Avocado", q: 2, u: "whole" },
      { k: "pickled red onion", n: "Pickled red onion", q: 80, u: "g", opt: true },
      { k: "smoked paprika", n: "Smoked paprika", q: 2, u: "tsp" },
    ],
    steps: [
      { t: "Fry the beans with paprika, mashing roughly as they warm.", m: 6 },
      { t: "Smash the avocado with lime and salt." },
      { t: "Build the tacos and top with pickled onion." },
    ],
    createdAt: T("2026-05-28"),
  },
  {
    id: "gnocchi-al-forno",
    name: "Gnocchi al forno",
    desc: "Baked gnocchi in tomato sauce under blistered mozzarella.",
    catalogue: "USER",
    quality: "IMPORTED",
    nutrition: "CALCULATED",
    img: IMG_PLATE,
    cuisine: "Italian",
    prep: 10,
    cook: 25,
    servings: 4,
    equipment: ["oven"],
    fridgeDays: 3,
    tags: {
      protein: null,
      cookingMethod: "bake",
      complexity: "MINIMAL",
      flavourProfile: ["tomato", "cheesy"],
      dietaryFlags: ["vegetarian"],
    },
    ing: [
      { k: "gnocchi", n: "Gnocchi", q: 800, u: "g" },
      { k: "tomato passata", n: "Tomato passata", q: 500, u: "g" },
      { k: "mozzarella", n: "Mozzarella", q: 250, u: "g" },
      { k: "parmesan", n: "Parmesan", q: 40, u: "g", opt: true },
      { k: "basil", n: "Basil", q: 1, u: "bunch" },
    ],
    steps: [
      { t: "Simmer the passata with garlic and a pinch of sugar.", m: 10 },
      { t: "Fold in the gnocchi and half the mozzarella." },
      { t: "Top with the rest and bake until blistered.", m: 15 },
    ],
    createdAt: T("2026-05-30"),
  },
  {
    id: "prawn-stir-fry",
    name: "Prawn stir-fry",
    desc: "The chicken stir-fry's pescatarian fork — king prawns, rice noodles, fish sauce.",
    catalogue: "USER",
    quality: "USER_VERIFIED",
    nutrition: "CALCULATED",
    img: IMG_BOWL,
    cuisine: "Thai",
    prep: 8,
    cook: 10,
    servings: 2,
    forkedFrom: "chicken-stir-fry",
    tags: {
      protein: "prawn",
      cookingMethod: "stir-fry",
      complexity: "MODERATE",
      flavourProfile: ["chilli", "fish sauce"],
      dietaryFlags: ["pescatarian"],
    },
    ing: [
      { k: "king prawn", n: "Raw king prawns", q: 250, u: "g" },
      { k: "rice noodle", n: "Rice noodles", q: 200, u: "g" },
      { k: "fish sauce", n: "Fish sauce", q: 2, u: "tbsp" },
      { k: "sugar snap pea", n: "Sugar snap peas", q: 150, u: "g" },
      { k: "chilli", n: "Chilli", q: 1, u: "whole", opt: true },
    ],
    steps: [
      { t: "Soak the noodles; flash-fry the prawns and set aside.", m: 6 },
      { t: "Stir-fry the vegetables with chilli and garlic.", m: 4 },
      { t: "Return the prawns with the sauce and toss through the noodles." },
    ],
    createdAt: T("2026-05-25"),
  },
  {
    id: "veggie-chilli",
    name: "Veggie chilli",
    desc: "Three-bean chilli batch — archived after the spring rotation.",
    catalogue: "USER",
    quality: "USER_VERIFIED",
    nutrition: "CALCULATED",
    img: IMG_BOWL,
    cuisine: "Mexican",
    prep: 15,
    cook: 35,
    servings: 6,
    freezerWeeks: 12,
    packable: true,
    archivedAt: T("2026-05-15"),
    tags: {
      protein: "mixed beans",
      cookingMethod: "one-pot",
      complexity: "MODERATE",
      flavourProfile: ["smoky", "spiced"],
      dietaryFlags: ["vegetarian", "vegan"],
    },
    ing: [
      { k: "kidney bean", n: "Kidney beans", q: 2, u: "tins" },
      { k: "black bean", n: "Black beans", q: 1, u: "tins" },
      { k: "chopped tomato", n: "Chopped tomatoes", q: 800, u: "g" },
      { k: "chipotle paste", n: "Chipotle paste", q: 2, u: "tbsp" },
      { k: "onion", n: "Onion", q: 2, u: "whole", prep: "diced" },
    ],
    steps: [
      { t: "Soften the onion, then bloom the chipotle paste.", m: 8 },
      { t: "Add beans and tomatoes; simmer low for 30 minutes.", m: 30 },
      { t: "Season, rest 10 minutes, serve with rice or jackets." },
    ],
    createdAt: T("2026-02-10"),
  },
  {
    id: "lemon-orzo-chicken",
    name: "Lemon orzo chicken",
    desc: "One-pot lemon orzo with chicken thighs — imported yesterday, nutrition still computing.",
    catalogue: "USER",
    quality: "IMPORTED",
    nutrition: "PENDING",
    img: null,
    cuisine: "Greek",
    prep: 10,
    cook: 30,
    servings: 4,
    fridgeDays: 3,
    tags: {
      protein: "chicken",
      cookingMethod: "one-pot",
      complexity: "MODERATE",
      flavourProfile: ["citrus", "herby"],
      dietaryFlags: [],
    },
    ing: [
      { k: "chicken thigh", n: "Chicken thighs", q: 6, u: "whole" },
      { k: "orzo", n: "Orzo", q: 300, u: "g" },
      { k: "lemon", n: "Lemon", q: 2, u: "whole" },
      { k: "chicken stock", n: "Chicken stock", q: 700, u: "ml" },
      { k: "oregano", n: "Oregano", q: 1, u: "tbsp" },
    ],
    steps: [
      { t: "Brown the thighs skin-side down; set aside.", m: 8 },
      { t: "Toast the orzo, add stock and lemon, return the chicken.", m: 5 },
      { t: "Simmer until the orzo is creamy and the chicken cooked.", m: 20 },
    ],
    createdAt: T("2026-06-09", "19:30"),
  },
];

/* Discovery-ingested system rows (the PARTIAL job's harvest + the cancelled
 * job's kept harvest). Already persisted before the user sees them (§5). */
const discoveredSpecs: RecipeSpec[] = [
  {
    id: "harissa-chickpea-traybake",
    name: "Harissa chickpea traybake",
    desc: "Crisped chickpeas and peppers under harissa yoghurt.",
    catalogue: "SYSTEM",
    quality: "WEB_DISCOVERED",
    nutrition: "PENDING",
    img: IMG_BOWL,
    cuisine: "Middle Eastern",
    prep: 10,
    cook: 25,
    servings: 4,
    actor: "discovery_pipeline",
    tags: {
      protein: "chickpea",
      cookingMethod: "traybake",
      complexity: "MINIMAL",
      flavourProfile: ["spiced"],
      dietaryFlags: ["vegetarian"],
    },
    ing: [
      { k: "chickpea", n: "Chickpeas", q: 2, u: "tins" },
      { k: "harissa", n: "Harissa paste", q: 2, u: "tbsp" },
      { k: "red pepper", n: "Red peppers", q: 2, u: "whole" },
      { k: "greek yoghurt", n: "Greek yoghurt", q: 150, u: "g" },
    ],
    steps: [
      { t: "Toss chickpeas and peppers in harissa and oil; roast 25 minutes.", m: 25 },
      { t: "Serve over yoghurt with warm flatbread." },
    ],
    createdAt: T("2026-06-07", "09:14"),
  },
  {
    id: "satay-noodles",
    name: "Peanut-free satay noodles",
    desc: "Sunflower-seed satay with rice noodles and charred greens.",
    catalogue: "SYSTEM",
    quality: "WEB_DISCOVERED",
    nutrition: "PENDING",
    img: IMG_PLATE,
    cuisine: "Thai",
    prep: 10,
    cook: 15,
    servings: 2,
    actor: "discovery_pipeline",
    tags: {
      protein: "tofu",
      cookingMethod: "stir-fry",
      complexity: "MODERATE",
      flavourProfile: ["nutty", "chilli"],
      dietaryFlags: ["vegetarian", "vegan"],
    },
    ing: [
      { k: "rice noodle", n: "Rice noodles", q: 200, u: "g" },
      { k: "sunflower seed butter", n: "Sunflower seed butter", q: 3, u: "tbsp" },
      { k: "tenderstem broccoli", n: "Tenderstem broccoli", q: 200, u: "g" },
      { k: "firm tofu", n: "Firm tofu", q: 200, u: "g" },
    ],
    steps: [
      { t: "Whisk the seed butter with soy, lime and chilli.", m: 5 },
      { t: "Char the greens, fry the tofu, toss everything with noodles.", m: 10 },
    ],
    createdAt: T("2026-06-07", "09:16"),
  },
  {
    id: "charred-corn-salad",
    name: "Charred corn & black bean salad",
    desc: "Smoky corn, black beans and lime — a no-cook-ish weeknight side or lunch.",
    catalogue: "SYSTEM",
    quality: "WEB_DISCOVERED",
    nutrition: "PENDING",
    img: IMG_TACOS,
    cuisine: "Mexican",
    prep: 15,
    cook: 5,
    servings: 4,
    mealTypes: ["LUNCH", "DINNER"],
    actor: "discovery_pipeline",
    tags: {
      protein: "black bean",
      cookingMethod: "assemble",
      complexity: "MINIMAL",
      flavourProfile: ["lime", "smoky"],
      dietaryFlags: ["vegetarian", "vegan"],
    },
    ing: [
      { k: "sweetcorn", n: "Sweetcorn", q: 3, u: "cobs" },
      { k: "black bean", n: "Black beans", q: 1, u: "tins" },
      { k: "lime", n: "Lime", q: 2, u: "whole" },
      { k: "coriander", n: "Coriander", q: 1, u: "bunch", opt: true },
    ],
    steps: [
      { t: "Char the corn in a dry pan; slice off the kernels.", m: 5 },
      { t: "Toss with beans, lime, oil and coriander." },
    ],
    createdAt: T("2026-06-07", "09:19"),
  },
  {
    id: "sticky-sesame-aubergine",
    name: "Sticky sesame aubergine",
    desc: "Glazed aubergine over rice — kept from a cancelled discovery run.",
    catalogue: "SYSTEM",
    quality: "WEB_DISCOVERED",
    nutrition: "PENDING",
    img: IMG_BOWL,
    cuisine: "Chinese",
    prep: 10,
    cook: 20,
    servings: 2,
    actor: "discovery_pipeline",
    tags: {
      protein: null,
      cookingMethod: "pan-fry",
      complexity: "MODERATE",
      flavourProfile: ["sticky", "sesame"],
      dietaryFlags: ["vegetarian", "vegan"],
    },
    ing: [
      { k: "aubergine", n: "Aubergines", q: 2, u: "whole" },
      { k: "soy sauce", n: "Soy sauce", q: 2, u: "tbsp" },
      { k: "honey", n: "Honey", q: 2, u: "tbsp" },
      { k: "sesame seed", n: "Sesame seeds", q: 1, u: "tbsp" },
    ],
    steps: [
      { t: "Steam-fry the aubergine until collapsing.", m: 12 },
      { t: "Add the glaze and reduce until sticky; scatter sesame.", m: 8 },
    ],
    createdAt: T("2026-06-08", "17:42"),
  },
];

/* ---- tofu-bibimbap: 3 versions on main + a diverged branch ------------------------- */

function buildBibimbap(): BuiltRecipe {
  const id = "tofu-bibimbap";
  const branchId = mainBranchId(id);
  const beefBranchId = `${id}-beef`;
  const metadata: NonNullable<RecipeVersionDto["metadata"]> = {
    servings: 4,
    prepTimeMins: 15,
    cookTimeMins: 10,
    totalTimeMins: 25,
    equipmentRequired: ["wok", "rice cooker"],
    fridgeDays: 3,
    freezerWeeks: null,
    packable: true,
    cuisine: "Korean",
    mealTypes: ["DINNER"],
  };
  const tags: RecipeTagsDto = {
    protein: "tofu",
    cookingMethod: "stir-fry",
    complexity: "MODERATE",
    flavourProfile: ["gochujang heat", "sesame"],
    dietaryFlags: ["vegetarian"],
  };
  const baseIng: IngSpec[] = [
    { k: "firm tofu", n: "Firm tofu", q: 400, u: "g", prep: "pressed, cubed" },
    { k: "short-grain rice", n: "Short-grain rice", q: 300, u: "g" },
    { k: "soy sauce", n: "Soy sauce", q: 4, u: "tbsp" },
    { k: "spinach", n: "Spinach", q: 150, u: "g" },
    { k: "carrot", n: "Carrots", q: 2, u: "whole", prep: "julienned" },
    { k: "gochujang", n: "Gochujang", q: 1.5, u: "tbsp" },
  ];
  const baseSteps: StepSpec[] = [
    { t: "Press the tofu for 15 minutes, then cube.", m: 15 },
    { t: "Cook the rice. Meanwhile fry the tofu until golden.", m: 10 },
    { t: "Blanch the spinach, dress with sesame; sauté the carrots briefly.", m: 5 },
  ];
  const v1 = makeVersion({
    recipeId: id, branchId, n: 1, trigger: "IMPORT",
    createdAt: T("2026-05-12", "10:20"),
    ing: baseIng, steps: baseSteps, metadata, tags,
  });
  const v2Ing: IngSpec[] = [
    ...baseIng.map((i) => (i.k === "gochujang" ? { ...i, q: 2 } : i)),
    { k: "sesame oil", n: "Sesame oil", q: 1, u: "tbsp" },
  ];
  const v2Steps: StepSpec[] = [
    { t: "Press the tofu for 15 minutes, then cube and toss in cornflour.", m: 15 },
    { t: "Cook the rice. Meanwhile fry the tofu until crisp on all sides.", m: 10 },
    { t: "Blanch the spinach, dress with sesame; sauté the carrots briefly.", m: 5 },
  ];
  const v2 = makeVersion({
    recipeId: id, branchId, n: 2, trigger: "MANUAL_EDIT",
    changeReason: "Crispier tofu (cornflour toss) and a bigger gochujang hit",
    createdAt: T("2026-05-26", "18:40"),
    parentVersionId: v1.id,
    ing: v2Ing, steps: v2Steps, metadata, tags,
  });
  const v3Ing = v2Ing.map((i) => (i.k === "soy sauce" ? { ...i, q: 3 } : i));
  const v3 = makeVersion({
    recipeId: id, branchId, n: 3, trigger: "ADAPTATION_PIPELINE",
    changeReason: "Reduce soy sauce 4 → 3 tbsp — from your feedback “too salty”",
    actor: "adaptation_pipeline",
    createdAt: T("2026-06-03", "07:55"),
    parentVersionId: v2.id,
    ing: v3Ing, steps: v2Steps, metadata, tags,
  });
  // Beef fork — branch-start version with a CROSS-BRANCH parent (main v2):
  // the diff expander hides on this row (422 cross-branch guard, §5b).
  const beefV1 = makeVersion({
    recipeId: id, branchId: beefBranchId, n: 1, trigger: "BRANCH_CREATION",
    changeReason: "Forked from main v2 — beef for tofu on request nights",
    createdAt: T("2026-05-30", "20:10"),
    parentVersionId: v2.id,
    idOverride: `${id}-beef-v1`,
    ing: [
      { k: "beef sirloin", n: "Beef sirloin", q: 350, u: "g", prep: "thinly sliced" },
      ...v2Ing.filter((i) => i.k !== "firm tofu"),
    ],
    steps: v2Steps,
    metadata,
    tags: { ...tags, protein: "beef", dietaryFlags: [] },
  });
  const mainBranch: RecipeBranchDto = {
    id: branchId, recipeId: id, parentBranchId: null, branchPointVersionId: null,
    name: "main", label: null, reason: null, currentVersion: 3,
    divergenceScore: 0, createdAt: T("2026-05-12", "10:20"),
    createdByActor: SELF_ACTOR, adapterTraceId: null, version: 3,
  };
  const beefBranch: RecipeBranchDto = {
    id: beefBranchId, recipeId: id, parentBranchId: branchId,
    branchPointVersionId: v2.id, name: "beef-variant", label: "Beef bibimbap",
    reason: "Protein swap fork — beef nights for Sam", currentVersion: 1,
    divergenceScore: 0.74, createdAt: T("2026-05-30", "20:10"),
    createdByActor: SELF_ACTOR, adapterTraceId: null, version: 1,
  };
  const dto: RecipeDto = {
    id,
    userId: MOCK_USER_ID,
    catalogue: "USER",
    name: "Crispy tofu bibimbap",
    description:
      "Rice bowl with crispy cornflour tofu, blanched greens and a gochujang sauce.",
    currentVersion: 3,
    currentBranchId: branchId,
    dataQuality: "USER_VERIFIED",
    nutritionStatus: "CALCULATED",
    forkedFromRecipeId: null,
    lastUsedInPlanAt: T("2026-06-08"),
    archivedAt: null,
    deletedAt: null,
    imageUrl: IMG_BOWL,
    optimisticVersion: 6,
    createdAt: T("2026-05-12", "10:20"),
    updatedAt: T("2026-06-03", "07:55"),
    currentVersionBody: v3,
    branches: [mainBranch, beefBranch],
  };
  return { dto, versions: { [branchId]: [v1, v2, v3], [beefBranchId]: [beefV1] } };
}

/* ---- substitutions ----------------------------------------------------------------- */

function makeSub(args: {
  id: string;
  recipeId: string;
  versionId: string;
  branchId: string;
  origKey: string;
  origQty: number;
  origUnit: string;
  subKey: string;
  subQty: number;
  subUnit: string;
  reason: RecipeSubstitutionDto["reason"];
  state: RecipeSubstitutionDto["state"];
  constraintRef?: string | null;
  notes?: string | null;
  temporary?: boolean;
  applicationCount?: number;
  lastAppliedAt?: string | null;
  methodOverlay?: Array<{ step: number; instruction: string }> | null;
  actor?: string;
  createdAt: string;
  version?: number;
}): RecipeSubstitutionDto {
  return {
    id: args.id,
    recipeId: args.recipeId,
    versionId: args.versionId,
    branchId: args.branchId,
    original: {
      ingredientMappingKey: args.origKey,
      quantity: args.origQty,
      unit: args.origUnit,
    },
    substitute: {
      ingredientMappingKey: args.subKey,
      quantity: args.subQty,
      unit: args.subUnit,
    },
    reason: args.reason,
    constraintRef: args.constraintRef ?? null,
    methodOverlay: args.methodOverlay ?? null,
    notes: args.notes ?? null,
    temporary: args.temporary ?? true,
    applicationCount: args.applicationCount ?? 0,
    lastAppliedAt: args.lastAppliedAt ?? null,
    state: args.state,
    promotedToVersionId: null,
    createdAt: args.createdAt,
    createdByActor: args.actor ?? "adaptation_pipeline",
    adapterTraceId: null,
    version: args.version ?? 1,
  };
}

/* ---- ratings ------------------------------------------------------------------------ */

let ratingSeq = 0;

function makeRating(args: {
  recipeId: string;
  versionId: string;
  taste: number;
  effort?: number;
  portion?: number;
  repeat?: number;
  notes?: string | null;
  createdAt: string;
  userId?: string;
}): RecipeRatingDto {
  const body = {
    taste: args.taste,
    effortWorthIt: args.effort ?? null,
    portionFit: args.portion ?? null,
    repeatValue: args.repeat ?? null,
  };
  return {
    id: `rate-${++ratingSeq}`,
    recipeId: args.recipeId,
    versionId: args.versionId,
    userId: args.userId ?? MOCK_USER_ID,
    householdId: null,
    slotId: null,
    ...body,
    aggregate: ratingAggregate(body),
    notes: args.notes ?? null,
    traceId: null,
    optimisticVersion: 1,
    createdAt: args.createdAt,
    updatedAt: args.createdAt,
  };
}

/* ---- provenance ------------------------------------------------------------------------ */

function makeProvenance(args: {
  recipeId: string;
  sourceType: RecipeImportDto["sourceType"];
  sourceUrl?: string | null;
  extractionMethod?: string | null;
  sourceKey?: string | null;
  importedAt: string;
  duplicateOfRecipeId?: string | null;
}): RecipeImportDto {
  return {
    id: `imp-${args.recipeId}`,
    recipeId: args.recipeId,
    sourceType: args.sourceType,
    sourceUrl: args.sourceUrl ?? null,
    sourcePayload: null,
    extractionMethod: args.extractionMethod ?? null,
    sourceKey: args.sourceKey ?? null,
    duplicateOfRecipeId: args.duplicateOfRecipeId ?? null,
    importedAt: args.importedAt,
    importedByUserId: MOCK_USER_ID,
  };
}

/* ---- assembled recipe seed ---------------------------------------------------------------- */

export function createRecipeSeed(): {
  recipes: RecipeDto[];
  recipeData: RecipeDataState;
} {
  const bibimbap = buildBibimbap();
  const built = [...baseSpecs, ...discoveredSpecs].map(buildRecipe);
  const recipes: RecipeDto[] = [bibimbap.dto, ...built.map((b) => b.dto)];

  const versions: RecipeDataState["versions"] = {
    [bibimbap.dto.id]: bibimbap.versions,
  };
  for (const b of built) versions[b.dto.id] = b.versions;

  // pasta-norma gets a v2 so a second recipe has history.
  const norma = versions["pasta-norma"][mainBranchId("pasta-norma")];
  const normaV1 = norma[0];
  const normaV2: RecipeVersionDto = {
    ...normaV1,
    id: "pasta-norma-v2",
    versionNumber: 2,
    parentVersionId: normaV1.id,
    trigger: "MANUAL_EDIT",
    changeReason: "Salt the aubergine first — less oil, deeper colour",
    createdAt: T("2026-06-01", "19:05"),
    methodSteps: normaV1.methodSteps.map((s) =>
      s.stepNumber === 1
        ? { ...s, instruction: "Salt the aubergine cubes 20 minutes, then fry until deeply golden." }
        : s,
    ),
  };
  norma.push(normaV2);
  const normaDto = recipes.find((r) => r.id === "pasta-norma") as RecipeDto;
  normaDto.currentVersion = 2;
  normaDto.currentVersionBody = normaV2;
  normaDto.optimisticVersion = 2;
  normaDto.updatedAt = normaV2.createdAt;
  normaDto.branches = normaDto.branches.map((b) =>
    b.name === "main" ? { ...b, currentVersion: 2 } : b,
  );

  const substitutions: RecipeDataState["substitutions"] = {
    "tofu-bibimbap": [
      makeSub({
        id: "sub-tamari",
        recipeId: "tofu-bibimbap",
        versionId: "tofu-bibimbap-v3",
        branchId: mainBranchId("tofu-bibimbap"),
        origKey: "soy sauce", origQty: 3, origUnit: "tbsp",
        subKey: "tamari", subQty: 3, subUnit: "tbsp",
        reason: "DIETARY_TEMP",
        state: "ACCEPTED",
        constraintRef: "gluten-trial-2026-06",
        notes: "Gluten-free trial fortnight — tamari swaps 1:1.",
        applicationCount: 3,
        lastAppliedAt: T("2026-06-08", "19:10"),
        createdAt: T("2026-05-29", "08:30"),
        version: 2,
      }),
      makeSub({
        id: "sub-sriracha",
        recipeId: "tofu-bibimbap",
        versionId: "tofu-bibimbap-v3",
        branchId: mainBranchId("tofu-bibimbap"),
        origKey: "gochujang", origQty: 2, origUnit: "tbsp",
        subKey: "sriracha", subQty: 1.5, subUnit: "tbsp",
        reason: "AVAILABILITY",
        state: "PROPOSED",
        notes: "Gochujang out of stock at Tesco this week.",
        methodOverlay: [
          { step: 3, instruction: "Blanch the spinach; whisk sriracha with a little sugar to mimic gochujang's sweetness." },
        ],
        createdAt: T("2026-06-09", "21:00"),
      }),
    ],
    "chicken-stir-fry": [
      makeSub({
        id: "sub-thigh",
        recipeId: "chicken-stir-fry",
        versionId: "chicken-stir-fry-v1",
        branchId: mainBranchId("chicken-stir-fry"),
        origKey: "chicken breast", origQty: 500, origUnit: "g",
        subKey: "chicken thigh", subQty: 500, subUnit: "g",
        reason: "BUDGET",
        state: "REJECTED",
        constraintRef: "budget-cap-2026-w23",
        notes: "Thigh is ~£1.10 cheaper per batch.",
        createdAt: T("2026-06-01", "07:45"),
        version: 2,
      }),
    ],
  };

  const ratings: RecipeDataState["ratings"] = {
    "tofu-bibimbap": [
      makeRating({
        recipeId: "tofu-bibimbap", versionId: "tofu-bibimbap-v3",
        taste: 86, effort: 78, portion: 90, repeat: 81,
        notes: "Crispier tofu made it.", createdAt: T("2026-06-04", "20:30"),
      }),
      makeRating({
        recipeId: "tofu-bibimbap", versionId: "tofu-bibimbap-v2",
        taste: 79, createdAt: T("2026-05-27", "20:10"),
      }),
    ],
    "chicken-stir-fry": [
      makeRating({
        recipeId: "chicken-stir-fry", versionId: "chicken-stir-fry-v1",
        taste: 82, effort: 88, portion: 84, repeat: 86,
        createdAt: T("2026-05-19", "20:00"),
      }),
    ],
    "salmon-traybake": [
      makeRating({
        recipeId: "salmon-traybake", versionId: "salmon-traybake-v1",
        taste: 88, effort: 90, portion: 82, repeat: 85,
        createdAt: T("2026-05-21", "20:45"),
      }),
    ],
    "pasta-norma": [
      makeRating({
        recipeId: "pasta-norma", versionId: "pasta-norma-v2",
        taste: 79, effort: 74, portion: 80, repeat: 72,
        notes: "Better since salting the aubergine.", createdAt: T("2026-06-02", "21:00"),
      }),
    ],
    "chickpea-spinach-curry": [
      makeRating({
        recipeId: "chickpea-spinach-curry", versionId: "chickpea-spinach-curry-v1",
        taste: 84, effort: 86, portion: 78, repeat: 80,
        createdAt: T("2026-05-23", "19:50"),
      }),
    ],
    "fish-tacos": [
      makeRating({
        recipeId: "fish-tacos", versionId: "fish-tacos-v1",
        taste: 81, effort: 76, portion: 83, repeat: 79,
        createdAt: T("2026-05-30", "20:20"),
      }),
    ],
    shakshuka: [
      makeRating({
        recipeId: "shakshuka", versionId: "shakshuka-v1",
        taste: 85, effort: 80, portion: 79, repeat: 83,
        createdAt: T("2026-06-07", "10:00"),
      }),
    ],
    "miso-salmon-traybake": [
      makeRating({
        recipeId: "miso-salmon-traybake", versionId: "miso-salmon-traybake-v1",
        taste: 87, effort: 84, portion: 81, repeat: 86,
        createdAt: T("2026-06-06", "20:15"),
      }),
    ],
    "black-bean-tacos": [
      makeRating({
        recipeId: "black-bean-tacos", versionId: "black-bean-tacos-v1",
        taste: 80, effort: 89, portion: 82, repeat: 78,
        createdAt: T("2026-05-31", "19:40"),
      }),
    ],
    "gnocchi-al-forno": [
      makeRating({
        recipeId: "gnocchi-al-forno", versionId: "gnocchi-al-forno-v1",
        taste: 83, effort: 77, portion: 85, repeat: 81,
        createdAt: T("2026-06-03", "20:55"),
      }),
    ],
    "prawn-stir-fry": [
      makeRating({
        recipeId: "prawn-stir-fry", versionId: "prawn-stir-fry-v1",
        taste: 82, effort: 90, portion: 77, repeat: 80,
        createdAt: T("2026-05-28", "20:05"),
      }),
    ],
    "veggie-chilli": [
      makeRating({
        recipeId: "veggie-chilli", versionId: "veggie-chilli-v1",
        taste: 76, effort: 82, portion: 88, repeat: 70,
        createdAt: T("2026-04-12", "19:30"),
      }),
    ],
    // tuna-melt + lemon-orzo-chicken + discovered rows: unrated ("—" cards).
  };

  const provenance: RecipeDataState["provenance"] = {
    "tofu-bibimbap": makeProvenance({
      recipeId: "tofu-bibimbap", sourceType: "URL",
      sourceUrl: "https://www.bonappetit.com/recipe/crispy-tofu-bibimbap",
      extractionMethod: "json_ld", importedAt: T("2026-05-12", "10:20"),
    }),
    "salmon-traybake": makeProvenance({
      recipeId: "salmon-traybake", sourceType: "URL",
      sourceUrl: "https://www.bbcgoodfood.com/recipes/salmon-traybake",
      extractionMethod: "json_ld", importedAt: T("2026-05-04"),
    }),
    "pasta-norma": makeProvenance({
      recipeId: "pasta-norma", sourceType: "URL",
      sourceUrl: "https://www.seriouseats.com/pasta-alla-norma",
      extractionMethod: "microdata", importedAt: T("2026-05-12"),
    }),
    "fish-tacos": makeProvenance({
      recipeId: "fish-tacos", sourceType: "URL",
      sourceUrl: "https://www.bonappetit.com/recipe/fish-tacos",
      extractionMethod: "json_ld", importedAt: T("2026-05-22"),
    }),
    "gnocchi-al-forno": makeProvenance({
      recipeId: "gnocchi-al-forno", sourceType: "URL",
      sourceUrl: "https://www.bbcgoodfood.com/recipes/gnocchi-al-forno",
      extractionMethod: "json_ld", importedAt: T("2026-05-30"),
    }),
    "lemon-orzo-chicken": makeProvenance({
      recipeId: "lemon-orzo-chicken", sourceType: "URL",
      sourceUrl: "https://www.bbcgoodfood.com/recipes/lemon-orzo-chicken",
      extractionMethod: "json_ld", importedAt: T("2026-06-09", "19:30"),
    }),
    // Graph-batch dish (G10): carries the 32-char generator audit stamp + campaign sourceKey the
    // detail page renders verbatim. black-bean-tacos below stays stamp-less — the no-provenance-
    // detail edge (chip renders, stamp line omitted, no error).
    "chickpea-spinach-curry": makeProvenance({
      recipeId: "chickpea-spinach-curry", sourceType: "AI_GENERATED",
      extractionMethod: "graph@395c11a+c@c81a2e87dacf339f",
      sourceKey: "graph:camp-2026-07-dinner1",
      importedAt: T("2026-05-20"),
    }),
    "black-bean-tacos": makeProvenance({
      recipeId: "black-bean-tacos", sourceType: "AI_GENERATED",
      importedAt: T("2026-05-28"),
    }),
    shakshuka: makeProvenance({
      recipeId: "shakshuka", sourceType: "WEB_DISCOVERED",
      sourceUrl: "https://ottolenghi.co.uk/recipes/shakshuka",
      extractionMethod: "common_selectors", importedAt: T("2026-06-02"),
    }),
    "miso-salmon-traybake": makeProvenance({
      recipeId: "miso-salmon-traybake", sourceType: "WEB_DISCOVERED",
      sourceUrl: "https://www.justonecookbook.com/miso-salmon",
      extractionMethod: "json_ld", importedAt: T("2026-06-02"),
    }),
    "harissa-chickpea-traybake": makeProvenance({
      recipeId: "harissa-chickpea-traybake", sourceType: "WEB_DISCOVERED",
      sourceUrl: "https://www.bbcgoodfood.com/recipes/harissa-chickpea-traybake",
      extractionMethod: "json_ld", importedAt: T("2026-06-07", "09:14"),
    }),
    "satay-noodles": makeProvenance({
      recipeId: "satay-noodles", sourceType: "WEB_DISCOVERED",
      sourceUrl: "https://www.seriouseats.com/peanut-free-satay-noodles",
      extractionMethod: "microdata", importedAt: T("2026-06-07", "09:16"),
    }),
    "charred-corn-salad": makeProvenance({
      recipeId: "charred-corn-salad", sourceType: "WEB_DISCOVERED",
      sourceUrl: "https://www.budgetbytes.com/charred-corn-black-bean-salad",
      extractionMethod: "common_selectors", importedAt: T("2026-06-07", "09:19"),
    }),
    "sticky-sesame-aubergine": makeProvenance({
      recipeId: "sticky-sesame-aubergine", sourceType: "WEB_DISCOVERED",
      sourceUrl: "https://www.bbcgoodfood.com/recipes/sticky-sesame-aubergine",
      extractionMethod: "json_ld", importedAt: T("2026-06-08", "17:42"),
    }),
  };

  return {
    recipes,
    recipeData: {
      versions,
      substitutions,
      ratings,
      provenance,
      nutritionByVersion: {},
    },
  };
}

/** Sanity hook for the seed: recipe-level summary used by dev assertions. */
export function seedSummaryFor(
  ratings: RecipeRatingDto[],
): ReturnType<typeof computeRatingSummary> {
  return computeRatingSummary(ratings);
}

/* ---- adaptation seed (PendingChangeDto pair + per-recipe history) ------------------- */

const PENDING_DETAIL: PendingChangeDto = {
  id: "pc-1",
  recipeId: "chicken-stir-fry",
  userId: MOCK_USER_ID,
  jobId: "adapt-job-77",
  traceId: "trace-adapt-77",
  changeDimension: "SALT_LEVEL",
  proposedClassification: "VERSION",
  baseVersionId: "chicken-stir-fry-v1",
  baseBranchId: mainBranchId("chicken-stir-fry"),
  proposedDiff: {
    ingredientChanges: [
      {
        action: "MODIFIED",
        from: { ingredientMappingKey: "soy sauce", displayName: "Soy sauce", quantity: 3, unit: "tbsp" },
        to: { ingredientMappingKey: "soy sauce", displayName: "Soy sauce", quantity: 2, unit: "tbsp" },
        fieldChanged: "quantity",
      },
    ],
    methodChanges: [],
    metadataChanges: [],
    tagChanges: [],
  },
  reasoning:
    "Your Tuesday feedback flagged the stir-fry as too salty. Soy sauce is the dominant sodium source in this recipe; reducing it from 3 to 2 tbsp keeps the umami profile while cutting sodium per serving by roughly a third. The velveting step already carries some soy, so the sauce can lose a tablespoon without tasting flat.",
  nutritionalNotes: "Sodium per serving drops ≈310 mg; no macro impact.",
  confidence: 0.88,
  impactScore: 0.72,
  promptTemplateVersion: "adapt-prompt-v3",
  status: "PENDING",
  supersededBy: null,
  acceptedVersionId: null,
  userEdits: null,
  createdAt: "2026-06-09T20:15:00Z",
  expiresAt: "2026-06-13T20:15:00Z",
  resolvedAt: null,
  optimisticVersion: 2,
};

const HISTORY_PORTION: PendingChangeDto = {
  ...PENDING_DETAIL,
  id: "pc-h1",
  changeDimension: "PORTION_SIZE",
  proposedClassification: "VERSION",
  proposedDiff: {
    ingredientChanges: [],
    methodChanges: [],
    metadataChanges: [{ action: "MODIFIED", field: "servings", from: 4, to: 5 }],
    tagChanges: [],
  },
  reasoning: "Portions trended small across the week's dinners; scale to 5 servings.",
  nutritionalNotes: null,
  confidence: 0.64,
  impactScore: 0.41,
  status: "REJECTED",
  createdAt: "2026-05-22T08:00:00Z",
  expiresAt: "2026-05-26T08:00:00Z",
  resolvedAt: "2026-05-23T19:12:00Z",
};

const HISTORY_TIME: PendingChangeDto = {
  ...PENDING_DETAIL,
  id: "pc-h2",
  changeDimension: "COOKING_TIME",
  reasoning: "Batch the velveting ahead to cut weeknight time by 8 minutes.",
  nutritionalNotes: null,
  confidence: 0.58,
  impactScore: 0.3,
  status: "EXPIRED",
  proposedDiff: { ingredientChanges: [], methodChanges: [], metadataChanges: [], tagChanges: [] },
  createdAt: "2026-04-30T08:00:00Z",
  expiresAt: "2026-05-04T08:00:00Z",
  resolvedAt: null,
};

/** #2 in the impact×confidence ranking — TEXTURE on the gnocchi, expiring <48h. */
const PENDING_TEXTURE: PendingChangeDto = {
  ...PENDING_DETAIL,
  id: "pc-2",
  recipeId: "gnocchi-al-forno",
  jobId: "adapt-job-81",
  traceId: "trace-adapt-81",
  changeDimension: "TEXTURE",
  proposedClassification: "VERSION",
  baseVersionId: "gnocchi-al-forno-v1",
  baseBranchId: mainBranchId("gnocchi-al-forno"),
  proposedDiff: {
    ingredientChanges: [],
    methodChanges: [
      {
        action: "MODIFIED",
        step: 4,
        from: "Bake for 25 minutes until bubbling.",
        to: "Bake for 20 minutes, then finish 5 minutes under a hot grill until the top crisps.",
      },
    ],
    metadataChanges: [],
    tagChanges: [],
  },
  reasoning:
    "Your texture likes lean hard toward crispy edges and charred finishes, and the gnocchi bake came back twice as “a bit soft all the way through”. Splitting the final bake into 20 minutes covered plus 5 under a hot grill keeps the middle creamy while giving the top the contrast you keep responding to.",
  nutritionalNotes: null,
  confidence: 0.81,
  impactScore: 0.62,
  status: "PENDING",
  createdAt: "2026-06-08T07:30:00Z",
  expiresAt: "2026-06-11T08:00:00Z",
  resolvedAt: null,
  optimisticVersion: 1,
};

/** #3 in the ranking — ACID_BALANCE proposed as a BRANCH on the veggie chilli. */
const PENDING_ACID: PendingChangeDto = {
  ...PENDING_DETAIL,
  id: "pc-3",
  recipeId: "veggie-chilli",
  jobId: "adapt-job-83",
  traceId: "trace-adapt-83",
  changeDimension: "ACID_BALANCE",
  proposedClassification: "BRANCH",
  baseVersionId: "veggie-chilli-v1",
  baseBranchId: mainBranchId("veggie-chilli"),
  proposedDiff: {
    ingredientChanges: [
      {
        action: "ADDED",
        from: null,
        to: { ingredientMappingKey: "lime", displayName: "Lime, juiced", quantity: 1, unit: null },
        fieldChanged: null,
      },
    ],
    methodChanges: [
      {
        action: "ADDED",
        step: 6,
        from: null,
        to: "Stir the lime juice through off the heat, just before serving.",
      },
    ],
    metadataChanges: [],
    tagChanges: [],
  },
  reasoning:
    "Acid reads consistently low in your chilli feedback — “flat” twice in three weeks. A squeeze of lime stirred through off the heat lifts it without changing the spice level the kids tolerate. Proposed as a variant so the original stays untouched for batch-cook weeks.",
  nutritionalNotes: "Negligible — ~4 kcal per serving.",
  confidence: 0.74,
  impactScore: 0.55,
  status: "PENDING",
  createdAt: "2026-06-07T06:45:00Z",
  expiresAt: "2026-06-21T06:45:00Z",
  resolvedAt: null,
  optimisticVersion: 1,
};

export function createAdaptationSeed(): AdaptationState {
  const listRow = (d: PendingChangeDto, preview: string) => ({
    id: d.id,
    recipeId: d.recipeId,
    changeDimension: d.changeDimension,
    reasoningPreview: preview,
    confidence: d.confidence,
    impactScore: d.impactScore,
    createdAt: d.createdAt,
    expiresAt: d.expiresAt,
    // status/optimisticVersion (#257) — carried straight from the detail DTO.
    status: d.status,
    resolvedAt: d.resolvedAt,
    optimisticVersion: d.optimisticVersion,
  });
  return {
    // Server-ranked best-first by impact × confidence (activity.md §3a).
    pendingChanges: [
      listRow(
        PENDING_DETAIL,
        "Reduce soy sauce in chicken stir-fry by a third — from your feedback on Tuesday, “too salty”",
      ),
      listRow(
        PENDING_TEXTURE,
        "Finish the gnocchi bake under the grill — your texture likes say crispy edges",
      ),
      listRow(
        PENDING_ACID,
        "Brighten the veggie chilli with lime stirred through at the end — acid reads low",
      ),
    ],
    detailById: {
      [PENDING_DETAIL.id]: PENDING_DETAIL,
      [PENDING_TEXTURE.id]: PENDING_TEXTURE,
      [PENDING_ACID.id]: PENDING_ACID,
    },
    historyByRecipe: {
      "chicken-stir-fry": [HISTORY_PORTION, HISTORY_TIME],
    },
  };
}

/* ---- discovery seed ------------------------------------------------------------------ */

const UA = "MealPrepBot/1.0 (+https://mealprep.example/bot)";

function makeSource(args: {
  key: string;
  name: string;
  kind: DiscoverySourceDto["kind"];
  baseUrl: string;
  enabled?: boolean;
  userDisabled?: boolean;
  rpm?: number;
  rpd?: number;
  robots?: boolean;
  failureStreak?: number;
  lastSuccessAt?: string | null;
  lastFailureAt?: string | null;
  notes?: string | null;
}): DiscoverySourceDto {
  return {
    id: `src-${args.key}`,
    sourceKey: args.key,
    displayName: args.name,
    kind: args.kind,
    baseUrl: args.baseUrl,
    enabled: args.enabled ?? true,
    // userDisabled — user-driven Settings toggle, distinct from admin `enabled`.
    userDisabled: args.userDisabled ?? false,
    requestsPerMinute: args.rpm ?? 10,
    requestsPerDay: args.rpd ?? 500,
    respectRobotsTxt: args.robots ?? true,
    userAgent: UA,
    failureStreak: args.failureStreak ?? 0,
    lastFailureAt: args.lastFailureAt ?? null,
    lastSuccessAt: args.lastSuccessAt ?? null,
    notes: args.notes ?? null,
    optimisticVersion: 1,
  };
}

const sourcesSeed: DiscoverySourceDto[] = [
  makeSource({
    key: "bbcgoodfood", name: "BBC Good Food", kind: "SITEMAP",
    baseUrl: "https://www.bbcgoodfood.com", rpm: 12, rpd: 600,
    lastSuccessAt: T(MOCK_TODAY_ISO, "06:10"),
  }),
  makeSource({
    key: "seriouseats", name: "Serious Eats", kind: "RSS_FEED",
    baseUrl: "https://www.seriouseats.com", rpm: 8, rpd: 400,
    lastSuccessAt: T("2026-06-09", "22:40"),
  }),
  makeSource({
    key: "budgetbytes", name: "Budget Bytes", kind: "CATEGORY_INDEX",
    baseUrl: "https://www.budgetbytes.com", rpm: 6, rpd: 300,
    failureStreak: 6,
    lastSuccessAt: T("2026-06-07", "09:19"),
    lastFailureAt: T(MOCK_TODAY_ISO, "05:50"),
    notes: "Circuit breaker open — retried hourly.",
  }),
  makeSource({
    key: "ottolenghi", name: "Ottolenghi", kind: "SITEMAP",
    baseUrl: "https://ottolenghi.co.uk", rpm: 4, rpd: 200,
    lastSuccessAt: T("2026-06-02", "07:30"),
    lastFailureAt: T("2026-06-07", "09:21"),
  }),
  makeSource({
    key: "justonecookbook", name: "Just One Cookbook", kind: "SITEMAP",
    baseUrl: "https://www.justonecookbook.com", rpm: 6, rpd: 300,
    lastSuccessAt: T("2026-06-02", "07:35"),
  }),
  makeSource({
    key: "recipe-search-api", name: "Recipe search API", kind: "SEARCH_API",
    baseUrl: "https://api.recipesearch.example", rpm: 30, rpd: 2000,
    robots: false,
    lastSuccessAt: T("2026-06-09", "22:41"),
  }),
  makeSource({
    key: "delicious-mag", name: "Delicious Magazine", kind: "CATEGORY_INDEX",
    baseUrl: "https://www.deliciousmagazine.co.uk", enabled: false,
    notes: "Disabled by admin — site relaunch broke the extractor.",
    lastSuccessAt: T("2026-05-18", "06:00"),
    lastFailureAt: T("2026-05-29", "06:00"),
  }),
];

let scrapeSeq = 0;

function logRow(args: {
  jobId: string;
  sourceKey: string;
  url: string;
  canonical?: string | null;
  status: ScrapeOutcome;
  skipReason?: ScrapeSkipReason | null;
  http?: number | null;
  robots?: RobotsTxtOutcome;
  latency?: number | null;
  method?: string | null;
  conf?: number | null;
  recipeId?: string | null;
  errorClass?: string | null;
  errorMessage?: string | null;
  at: string;
}): DiscoveryScrapeLogEntryDto {
  return {
    id: `scrape-${++scrapeSeq}`,
    jobId: args.jobId,
    sourceKey: args.sourceKey,
    candidateUrl: args.url,
    canonicalUrl: args.canonical ?? null,
    status: args.status,
    httpStatusCode: args.http ?? null,
    robotsTxtOutcome: args.robots ?? "ALLOWED",
    latencyMs: args.latency ?? null,
    contentFingerprint:
      args.status === "SUCCESS" || args.status === "DUPLICATE"
        ? `fp-${scrapeSeq}`
        : null,
    extractionMethod: args.method ?? null,
    extractionConfidence: args.conf ?? null,
    recipeId: args.recipeId ?? null,
    skipReason: args.skipReason ?? null,
    errorClass: args.errorClass ?? null,
    errorMessage: args.errorMessage ?? null,
    occurredAt: args.at,
  };
}

/** The user's hard-constraint snapshot, pre-normalised to mapping keys —
 *  computed by the CALLER per the LLD (client-trust hole, discover.md §9 Q3). */
export const HARD_CONSTRAINT_KEYS = [
  "peanut",
  "peanut butter",
  "almond",
  "cashew",
  "walnut",
];

const PARTIAL_JOB_ID = "djob-w24-veg";
const SWEEP_JOB_ID = "djob-w23-sweep";
const CANCELLED_JOB_ID = "djob-w24-cancel";

function partialJobLog(): DiscoveryScrapeLogEntryDto[] {
  const j = PARTIAL_JOB_ID;
  const d = "2026-06-07";
  return [
    logRow({
      jobId: j, sourceKey: "bbcgoodfood",
      url: "https://www.bbcgoodfood.com/recipes/harissa-chickpea-traybake",
      status: "SUCCESS", http: 200, latency: 420, method: "json_ld", conf: 0.93,
      recipeId: "harissa-chickpea-traybake", at: T(d, "09:14"),
    }),
    logRow({
      jobId: j, sourceKey: "bbcgoodfood",
      url: "https://www.bbcgoodfood.com/recipes/collection/vegetarian-one-pot",
      status: "EXTRACTION_FAILED", http: 200, latency: 380,
      errorClass: "NoRecipeMarkupException",
      errorMessage: "Roundup page — no recipe entity found in markup",
      at: T(d, "09:14"),
    }),
    logRow({
      jobId: j, sourceKey: "bbcgoodfood",
      url: "https://www.bbcgoodfood.com/recipes/easy-shakshuka",
      canonical: "https://www.bbcgoodfood.com/recipes/shakshuka",
      status: "DUPLICATE", skipReason: "DUPLICATE", http: 200, latency: 350,
      method: "json_ld", conf: 0.95, at: T(d, "09:15"),
    }),
    logRow({
      jobId: j, sourceKey: "seriouseats",
      url: "https://www.seriouseats.com/peanut-free-satay-noodles",
      status: "SUCCESS", http: 200, latency: 510, method: "microdata", conf: 0.88,
      recipeId: "satay-noodles", at: T(d, "09:16"),
    }),
    logRow({
      jobId: j, sourceKey: "seriouseats",
      url: "https://www.seriouseats.com/classic-peanut-noodles",
      status: "HARD_CONSTRAINT_VIOLATION", skipReason: "HARD_CONSTRAINT",
      http: 200, latency: 470, method: "microdata", conf: 0.91, at: T(d, "09:16"),
    }),
    logRow({
      jobId: j, sourceKey: "seriouseats",
      url: "https://www.seriouseats.com/cream-laden-carbonara",
      status: "SKIPPED", skipReason: "AI_FILTER_REJECTED",
      http: 200, latency: 440, method: "microdata", conf: 0.86, at: T(d, "09:17"),
    }),
    logRow({
      jobId: j, sourceKey: "budgetbytes",
      url: "https://www.budgetbytes.com/charred-corn-black-bean-salad",
      status: "SUCCESS", http: 200, latency: 610, method: "common_selectors",
      conf: 0.81, recipeId: "charred-corn-salad", at: T(d, "09:19"),
    }),
    logRow({
      jobId: j, sourceKey: "budgetbytes",
      url: "https://www.budgetbytes.com/mystery-casserole",
      status: "SKIPPED", skipReason: "LOW_CONFIDENCE",
      http: 200, latency: 590, method: "common_selectors", conf: 0.41,
      at: T(d, "09:19"),
    }),
    logRow({
      jobId: j, sourceKey: "budgetbytes",
      url: "https://www.budgetbytes.com/15-bean-soup",
      status: "RATE_LIMITED", skipReason: "RATE_LIMITED", at: T(d, "09:20"),
    }),
    logRow({
      jobId: j, sourceKey: "budgetbytes",
      url: "https://www.budgetbytes.com/sweet-potato-tacos",
      status: "SKIPPED", skipReason: "AI_FILTER_REJECTED",
      http: 200, latency: 540, method: "common_selectors", conf: 0.83,
      at: T(d, "09:20"),
    }),
    logRow({
      jobId: j, sourceKey: "ottolenghi",
      url: "https://ottolenghi.co.uk/recipes/print/aubergine-dumplings",
      status: "ROBOTS_DISALLOWED", skipReason: "ROBOTS_DISALLOWED",
      robots: "DISALLOWED", at: T(d, "09:21"),
    }),
    logRow({
      jobId: j, sourceKey: "ottolenghi",
      url: "https://ottolenghi.co.uk/recipes/charred-spring-onions",
      status: "HTTP_ERROR", http: 503, latency: 2100,
      errorClass: "HttpServerErrorException",
      errorMessage: "503 Service Unavailable after 3 retries",
      at: T(d, "09:21"),
    }),
  ];
}

function sweepJobLog(): DiscoveryScrapeLogEntryDto[] {
  const j = SWEEP_JOB_ID;
  const d = "2026-06-02";
  return [
    logRow({
      jobId: j, sourceKey: "ottolenghi",
      url: "https://ottolenghi.co.uk/recipes/shakshuka",
      status: "SUCCESS", http: 200, latency: 480, method: "common_selectors",
      conf: 0.84, recipeId: "shakshuka", at: T(d, "07:30"),
    }),
    logRow({
      jobId: j, sourceKey: "justonecookbook",
      url: "https://www.justonecookbook.com/miso-salmon",
      status: "SUCCESS", http: 200, latency: 390, method: "json_ld",
      conf: 0.95, recipeId: "miso-salmon-traybake", at: T(d, "07:35"),
    }),
    logRow({
      jobId: j, sourceKey: "justonecookbook",
      url: "https://www.justonecookbook.com/karaage-fried-chicken",
      status: "SKIPPED", skipReason: "JOB_QUOTA_REACHED", at: T(d, "07:36"),
    }),
  ];
}

function cancelledJobLog(): DiscoveryScrapeLogEntryDto[] {
  const j = CANCELLED_JOB_ID;
  const d = "2026-06-08";
  return [
    logRow({
      jobId: j, sourceKey: "bbcgoodfood",
      url: "https://www.bbcgoodfood.com/recipes/sticky-sesame-aubergine",
      status: "SUCCESS", http: 200, latency: 405, method: "json_ld", conf: 0.9,
      recipeId: "sticky-sesame-aubergine", at: T(d, "17:42"),
    }),
    logRow({
      jobId: j, sourceKey: "bbcgoodfood",
      url: "https://www.bbcgoodfood.com/recipes/halloumi-burgers",
      status: "SKIPPED", skipReason: "AI_FILTER_REJECTED",
      http: 200, latency: 440, method: "json_ld", conf: 0.87, at: T(d, "17:43"),
    }),
  ];
}

export function createDiscoverySeed(): DiscoveryState {
  const partial: DiscoveryJobDto = {
    id: PARTIAL_JOB_ID,
    userId: MOCK_USER_ID,
    trigger: "USER_INITIATED",
    requestedCount: 10,
    constraints: {
      schemaVersion: 1,
      requiredCuisines: null,
      requiredMealTypes: ["DINNER"],
      maxTotalTimeMins: 35,
      mustExcludeIngredientMappingKeys: HARD_CONSTRAINT_KEYS,
      dietaryFlags: ["vegetarian"],
      preferenceHints: ["vegetarian one-pot dinners"],
      maxRecipesPerSource: 4,
    },
    sourcesRequested: ["bbcgoodfood", "seriouseats", "budgetbytes", "ottolenghi"],
    status: "PARTIAL",
    queuedAt: T("2026-06-07", "09:13"),
    startedAt: T("2026-06-07", "09:14"),
    completedAt: T("2026-06-07", "09:22"),
    candidatesSeen: 12,
    candidatesAfterFilter: 10,
    recipesIngested: 3,
    recipesSkippedDuplicate: 1,
    sourcesSucceeded: ["bbcgoodfood", "seriouseats", "budgetbytes"],
    sourcesFailed: ["ottolenghi"],
    errorSummary: "ottolenghi.co.uk failed: HTTP 503 after 3 retries",
    traceId: "trace-djob-w24-veg",
    optimisticVersion: 3,
  };
  const sweep: DiscoveryJobDto = {
    id: SWEEP_JOB_ID,
    userId: MOCK_USER_ID,
    trigger: "SCHEDULED",
    requestedCount: 2,
    constraints: {
      schemaVersion: 1,
      requiredCuisines: null,
      requiredMealTypes: null,
      maxTotalTimeMins: null,
      mustExcludeIngredientMappingKeys: HARD_CONSTRAINT_KEYS,
      dietaryFlags: null,
      preferenceHints: null,
      maxRecipesPerSource: null,
    },
    sourcesRequested: ["ottolenghi", "justonecookbook"],
    status: "SUCCEEDED",
    queuedAt: T("2026-06-02", "07:29"),
    startedAt: T("2026-06-02", "07:30"),
    completedAt: T("2026-06-02", "07:36"),
    candidatesSeen: 3,
    candidatesAfterFilter: 3,
    recipesIngested: 2,
    recipesSkippedDuplicate: 0,
    sourcesSucceeded: ["ottolenghi", "justonecookbook"],
    sourcesFailed: [],
    errorSummary: null,
    traceId: "trace-djob-w23-sweep",
    optimisticVersion: 2,
  };
  const cancelled: DiscoveryJobDto = {
    id: CANCELLED_JOB_ID,
    userId: MOCK_USER_ID,
    trigger: "USER_INITIATED",
    requestedCount: 8,
    constraints: {
      schemaVersion: 1,
      requiredCuisines: ["Chinese"],
      requiredMealTypes: ["DINNER"],
      maxTotalTimeMins: 30,
      mustExcludeIngredientMappingKeys: HARD_CONSTRAINT_KEYS,
      dietaryFlags: null,
      preferenceHints: ["sticky glazed veg mains"],
      maxRecipesPerSource: null,
    },
    sourcesRequested: ["bbcgoodfood", "seriouseats"],
    status: "FAILED",
    queuedAt: T("2026-06-08", "17:41"),
    startedAt: T("2026-06-08", "17:42"),
    completedAt: T("2026-06-08", "17:44"),
    candidatesSeen: 2,
    candidatesAfterFilter: 1,
    recipesIngested: 1,
    recipesSkippedDuplicate: 0,
    sourcesSucceeded: ["bbcgoodfood"],
    sourcesFailed: [],
    errorSummary: "cancelled by user",
    traceId: "trace-djob-w24-cancel",
    optimisticVersion: 4,
  };
  return {
    jobs: [cancelled, partial, sweep],
    scrapeLog: {
      [PARTIAL_JOB_ID]: partialJobLog(),
      [SWEEP_JOB_ID]: sweepJobLog(),
      [CANCELLED_JOB_ID]: cancelledJobLog(),
    },
    sources: sourcesSeed,
    openJobId: PARTIAL_JOB_ID,
    skippedRowIds: [],
    cancelRequested: null,
  };
}

/* ---- canned fetch script for NEW discovery runs ------------------------------------- */

export interface ScriptedFetch {
  sourceKey: string;
  url: string;
  status: ScrapeOutcome;
  skipReason?: ScrapeSkipReason;
  http?: number;
  latency?: number;
  robots?: RobotsTxtOutcome;
  method?: string;
  conf?: number;
  errorClass?: string;
  errorMessage?: string;
  /** SUCCESS rows: the recipe persisted into the system catalogue. */
  recipe?: RecipeSpec;
}

const runRecipe = (
  id: string,
  name: string,
  cuisine: string,
  prep: number,
  cook: number,
  ing: IngSpec[],
  steps: StepSpec[],
): RecipeSpec => ({
  id,
  name,
  desc: `${name} — ingested by discovery, pending nutrition.`,
  catalogue: "SYSTEM",
  quality: "WEB_DISCOVERED",
  nutrition: "PENDING",
  img: null,
  cuisine,
  prep,
  cook,
  servings: 4,
  actor: "discovery_pipeline",
  tags: null,
  ing,
  steps,
  createdAt: T(MOCK_TODAY_ISO, "18:05"),
});

/** Replayed row-by-row by the mock runner (~700 ms cadence). */
export const DISCOVERY_RUN_SCRIPT: ScriptedFetch[] = [
  {
    sourceKey: "bbcgoodfood",
    url: "https://www.bbcgoodfood.com/recipes/white-bean-kale-traybake",
    status: "SUCCESS", http: 200, latency: 430, method: "json_ld", conf: 0.92,
    recipe: runRecipe(
      "white-bean-kale-traybake", "White bean & kale traybake", "Mediterranean", 10, 25,
      [
        { k: "cannellini bean", n: "Cannellini beans", q: 2, u: "tins" },
        { k: "kale", n: "Kale", q: 200, u: "g" },
        { k: "cherry tomato", n: "Cherry tomatoes", q: 300, u: "g" },
        { k: "garlic", n: "Garlic", q: 4, u: "cloves" },
      ],
      [
        { t: "Roast beans, tomatoes and garlic with oil for 20 minutes.", m: 20 },
        { t: "Stir through the kale; roast 5 minutes more.", m: 5 },
      ],
    ),
  },
  {
    sourceKey: "bbcgoodfood",
    url: "https://www.bbcgoodfood.com/recipes/best-ever-chicken-stir-fry",
    status: "DUPLICATE", skipReason: "DUPLICATE", http: 200, latency: 380,
    method: "json_ld", conf: 0.94,
  },
  {
    sourceKey: "seriouseats",
    url: "https://www.seriouseats.com/smoky-tomato-orzo",
    status: "SUCCESS", http: 200, latency: 520, method: "microdata", conf: 0.87,
    recipe: runRecipe(
      "smoky-tomato-orzo", "Smoky tomato orzo", "Greek", 10, 20,
      [
        { k: "orzo", n: "Orzo", q: 300, u: "g" },
        { k: "chopped tomato", n: "Chopped tomatoes", q: 400, u: "g" },
        { k: "smoked paprika", n: "Smoked paprika", q: 2, u: "tsp" },
        { k: "feta", n: "Feta", q: 100, u: "g", opt: true },
      ],
      [
        { t: "Toast the orzo in oil with paprika.", m: 4 },
        { t: "Add tomatoes and stock; simmer until creamy.", m: 16 },
      ],
    ),
  },
  {
    sourceKey: "seriouseats",
    url: "https://www.seriouseats.com/thai-peanut-curry",
    status: "HARD_CONSTRAINT_VIOLATION", skipReason: "HARD_CONSTRAINT",
    http: 200, latency: 460, method: "microdata", conf: 0.9,
  },
  {
    sourceKey: "bbcgoodfood",
    url: "https://www.bbcgoodfood.com/recipes/triple-cream-pasta",
    status: "SKIPPED", skipReason: "AI_FILTER_REJECTED",
    http: 200, latency: 410, method: "json_ld", conf: 0.89,
  },
  {
    sourceKey: "justonecookbook",
    url: "https://www.justonecookbook.com/crispy-gnocchi-miso-butter",
    status: "SUCCESS", http: 200, latency: 470, method: "json_ld", conf: 0.91,
    recipe: runRecipe(
      "crispy-gnocchi-miso-butter", "Crispy gnocchi with miso butter", "Japanese", 5, 15,
      [
        { k: "gnocchi", n: "Gnocchi", q: 600, u: "g" },
        { k: "white miso", n: "White miso", q: 1.5, u: "tbsp" },
        { k: "butter", n: "Butter", q: 40, u: "g" },
        { k: "spring green", n: "Spring greens", q: 200, u: "g" },
      ],
      [
        { t: "Pan-fry the gnocchi until blistered and crisp.", m: 8 },
        { t: "Add miso butter and greens; toss until glossy.", m: 6 },
      ],
    ),
  },
  {
    sourceKey: "seriouseats",
    url: "https://www.seriouseats.com/forty-clove-chicken",
    status: "SKIPPED", skipReason: "LOW_CONFIDENCE",
    http: 200, latency: 530, method: "common_selectors", conf: 0.38,
  },
  {
    sourceKey: "justonecookbook",
    url: "https://www.justonecookbook.com/print/sukiyaki",
    status: "ROBOTS_DISALLOWED", skipReason: "ROBOTS_DISALLOWED",
    robots: "DISALLOWED",
  },
  {
    sourceKey: "bbcgoodfood",
    url: "https://www.bbcgoodfood.com/recipes/lemon-tahini-cauliflower",
    status: "SUCCESS", http: 200, latency: 415, method: "json_ld", conf: 0.85,
    recipe: runRecipe(
      "lemon-tahini-cauliflower", "Lemon tahini roast cauliflower", "Middle Eastern", 10, 30,
      [
        { k: "cauliflower", n: "Cauliflower", q: 1, u: "head" },
        { k: "tahini", n: "Tahini", q: 3, u: "tbsp" },
        { k: "lemon", n: "Lemon", q: 1, u: "whole" },
        { k: "pomegranate seed", n: "Pomegranate seeds", q: 80, u: "g", opt: true },
      ],
      [
        { t: "Roast cauliflower florets hot for 25 minutes.", m: 25 },
        { t: "Whisk tahini, lemon and water; dress and scatter seeds.", m: 5 },
      ],
    ),
  },
  {
    sourceKey: "seriouseats",
    url: "https://www.seriouseats.com/server-meltdown-stew",
    status: "HTTP_ERROR", http: 500, latency: 1800,
    errorClass: "HttpServerErrorException",
    errorMessage: "500 Internal Server Error",
  },
];

/** Build the scrape-log row for one scripted fetch. */
export function rowFromScript(
  jobId: string,
  f: ScriptedFetch,
  recipeId: string | null,
  at: string,
): DiscoveryScrapeLogEntryDto {
  return logRow({
    jobId,
    sourceKey: f.sourceKey,
    url: f.url,
    status: f.status,
    skipReason: f.skipReason ?? null,
    http: f.http ?? null,
    robots: f.robots ?? (f.sourceKey === "recipe-search-api" ? "SKIPPED" : "ALLOWED"),
    latency: f.latency ?? null,
    method: f.method ?? null,
    conf: f.conf ?? null,
    recipeId,
    errorClass: f.errorClass ?? null,
    errorMessage: f.errorMessage ?? null,
    at,
  });
}

/* ---- import preview fixtures (recipes.md §4a) --------------------------------------- */

/** Pasting this URL previews a near-duplicate of chicken-stir-fry (≥80 %
 *  ingredient overlap) — exercises the §4c dedup dialog end-to-end. */
export const DEDUP_DEMO_URL =
  "https://www.seriouseats.com/the-best-chicken-stir-fry";

export const DEDUP_PARSED_RECIPE: CreateRecipeRequest = {
  name: "The best chicken stir-fry",
  description: "Wok-seared chicken with greens and a glossy soy sauce.",
  ingredients: [
    { lineOrder: 0, ingredientMappingKey: "chicken breast", displayName: "Chicken breast", quantity: 450, unit: "g" },
    { lineOrder: 1, ingredientMappingKey: "egg noodle", displayName: "Egg noodles", quantity: 250, unit: "g" },
    { lineOrder: 2, ingredientMappingKey: "soy sauce", displayName: "Soy sauce", quantity: 2.5, unit: "tbsp" },
    { lineOrder: 3, ingredientMappingKey: "broccoli", displayName: "Broccoli", quantity: 1, unit: "head" },
    { lineOrder: 4, ingredientMappingKey: "ginger", displayName: "Ginger", quantity: 15, unit: "g", preparation: "grated" },
    { lineOrder: 5, ingredientMappingKey: "garlic", displayName: "Garlic", quantity: 2, unit: "cloves" },
  ],
  method: [
    { stepNumber: 1, instruction: "Marinate the chicken in soy and cornflour." },
    { stepNumber: 2, instruction: "Sear hard in a smoking wok, in batches.", durationMinutes: 6 },
    { stepNumber: 3, instruction: "Add vegetables, sauce and noodles; toss to coat.", durationMinutes: 5 },
  ],
  metadata: {
    servings: 4,
    prepTimeMins: 12,
    cookTimeMins: 11,
    totalTimeMins: 23,
    equipmentRequired: ["wok"],
    packable: true,
    cuisine: "Chinese",
    mealTypes: ["DINNER"],
  },
  tags: null,
};

export const GENERIC_PARSED_RECIPE: CreateRecipeRequest = {
  name: "Sticky ginger noodles",
  description: "Quick glazed noodles with charred spring greens.",
  ingredients: [
    { lineOrder: 0, ingredientMappingKey: "udon noodle", displayName: "Udon noodles", quantity: 400, unit: "g" },
    { lineOrder: 1, ingredientMappingKey: "ginger", displayName: "Ginger", quantity: 30, unit: "g", preparation: "grated" },
    { lineOrder: 2, ingredientMappingKey: "spring green", displayName: "Spring greens", quantity: 200, unit: "g" },
    { lineOrder: 3, ingredientMappingKey: "honey", displayName: "Honey", quantity: 2, unit: "tbsp" },
    { lineOrder: 4, ingredientMappingKey: "rice vinegar", displayName: "Rice vinegar", quantity: 1, unit: "tbsp" },
  ],
  method: [
    { stepNumber: 1, instruction: "Char the greens in a dry pan.", durationMinutes: 4 },
    { stepNumber: 2, instruction: "Fry the ginger, add honey, vinegar and a splash of soy.", durationMinutes: 3 },
    { stepNumber: 3, instruction: "Toss the noodles through the glaze with the greens.", durationMinutes: 3 },
  ],
  metadata: {
    servings: 2,
    prepTimeMins: 10,
    cookTimeMins: 10,
    totalTimeMins: 20,
    equipmentRequired: [],
    packable: false,
    cuisine: "Chinese",
    mealTypes: ["DINNER"],
  },
  tags: null,
};

export const GENERIC_PARSE_WARNINGS: string[] = [
  "Couldn't parse ingredient line “a glug of toasted sesame oil” — quantity missing",
  "Stated total time (25 min) doesn't match prep + cook (20 min); using prep + cook",
];
