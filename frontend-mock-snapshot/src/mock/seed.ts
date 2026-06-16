/**
 * Seed data for the mock store — ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and rebuilt
 * slice-by-slice onto the production contract DTOs as each page spec lands.
 */

import { createGrocerySeed, createPantrySeed } from "./groceryPantrySeed";
import { createNutritionSeed, targetsSeed } from "./nutritionSeed";
import { createPlannerSeed } from "./plannerSeed";
import { createActivitySeed, createPreferencesSeed } from "./prefActivitySeed";
import {
  createAdaptationSeed,
  createDiscoverySeed,
  createRecipeSeed,
} from "./recipeSeed";
import {
  createAdminSeed,
  createHouseholdSeed,
  createNotificationsSeed,
  createSessionSeed,
} from "./settingsAdminSeed";
import type { StoreState } from "./types";

/** The mock's fixed "today" (Wednesday 10 June 2026) — keeps expiry colour
 *  coding and date labels deterministic. Defined with the nutrition seed,
 *  whose intake days are keyed on the same week. */
export { MOCK_TODAY_ISO } from "./nutritionSeed";

export function createSeed(): StoreState {
  return {
    planner: createPlannerSeed(),
    adaptation: createAdaptationSeed(),
    ...createRecipeSeed(),
    grocery: createGrocerySeed(),
    pantry: createPantrySeed(),
    notifications: createNotificationsSeed(),
    nutrition: createNutritionSeed(),
    targets: targetsSeed,
    preferences: createPreferencesSeed(),
    activity: createActivitySeed(),
    household: createHouseholdSeed(),
    session: createSessionSeed(),
    admin: createAdminSeed(),
    discovery: createDiscoverySeed(),
    toasts: [],
  };
}
