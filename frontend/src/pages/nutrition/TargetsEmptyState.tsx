/**
 * Targets-404 empty state (nutrition.md paragraphs 4 and 8): both the Overview
 * and the Targets tab render this initialise CTA instead of an error when no
 * targets row exists.
 */

import { AdvisorCard } from "../../components/AdvisorCard";
import { initialiseTargets } from "../../mock/store";
import type {
  EnforcementDirection,
  MacroTargetDto,
  UpdateTargetsRequest,
} from "../../mock/types";

/**
 * The onboarding wizard's suggested maintain defaults, reused verbatim as the
 * initialise payload. Authored scaffolding, not learned values: the user edits
 * everything on the Targets tab afterwards. Micros are omitted on purpose so
 * the server DRI-seeds the full tracked set.
 */
function defaultInitialiseRequest(): UpdateTargetsRequest {
  const calories = 2100;
  const macro = (
    targetG: number,
    direction: EnforcementDirection,
  ): MacroTargetDto => ({
    targetG,
    floorG: null,
    enforcement: "DAILY",
    direction,
    isHardFloor: false,
  });
  return {
    goal: "MAINTAIN",
    calories: {
      dailyTarget: calories,
      toleranceUnder: 100,
      toleranceOver: 50,
      enforcement: "DAILY",
      direction: "UPPER_LIMIT",
    },
    protein: macro(Math.round((calories * 0.25) / 4), "LOWER_FLOOR"),
    carbs: macro(Math.round((calories * 0.45) / 4), "UPPER_LIMIT"),
    fat: macro(Math.round((calories * 0.3) / 9), "BOTH_BOUNDED"),
    fibre: macro(30, "LOWER_FLOOR"),
    satFat: macro(Math.round((calories * 0.1) / 9), "UPPER_LIMIT"),
    notes: null,
    perMealDistribution: [],
    microTargets: [],
    eatingWindow: null,
    activityAdjustments: [],
    expectedVersion: 0,
  };
}

export function TargetsEmptyState() {
  return (
    <AdvisorCard
      label="Nutrition targets"
      title="No targets yet"
      sub="Initialise from your lifestyle to seed calories, macros and the DRI micro defaults. Everything stays editable on the Targets tab."
      actions={
        <button
          className="btn btn-primary"
          onClick={() => initialiseTargets(defaultInitialiseRequest())}
        >
          Initialise from your lifestyle
        </button>
      }
    />
  );
}
