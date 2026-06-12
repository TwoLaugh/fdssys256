/**
 * Types for the in-memory mock store that makes the app fully playable
 * without a backend. Shapes are ported from the D6 mockup fixtures
 * (design/frontend/mockups/directions/data.js + data-d6.js) and expanded
 * where pages need more.
 *
 * The nutrition slices are the exception: they mirror the real backend DTOs
 * (re-exported from the generated OpenAPI types below) so the mock validates
 * the production contract — see design/frontend/pages/nutrition.md.
 */

import type { components } from "../api/types.gen";

/* ---- plan: backend DTO mirrors ------------------------------------------------
 * The planner slices mirror the real contract (design/frontend/pages/plan.md §2)
 * exactly like the nutrition slices below — re-exported from the generated
 * OpenAPI types so the mock validates the production shapes.
 */

export type MealSlotKey = "breakfast" | "lunch" | "dinner";

type PlannerSchemas = components["schemas"];

export type PlanStatus = PlannerSchemas["PlanStatus"];
export type TriggerKind = PlannerSchemas["TriggerKind"];
export type PlannerSlotKind = PlannerSchemas["PlannerSlotKind"];
/** Planner slot lifecycle: PLANNED → COOKING → COOKED → EATEN | SKIPPED. */
export type SlotState = PlannerSchemas["SlotState"];
export type PinnedReason = NonNullable<PlannerSchemas["MealSlotDto"]["pinnedReason"]>;
export type ReoptTriggerKind = PlannerSchemas["ReoptTriggerKind"];
export type ConflictType = PlannerSchemas["ConflictType"];

export type MealSlotDto = PlannerSchemas["MealSlotDto"];
export type ScheduledRecipeRef = NonNullable<MealSlotDto["scheduledRecipe"]>;
export type DayDto = PlannerSchemas["DayDto"];
export type PlanDto = PlannerSchemas["PlanDto"];
export type ScoreBreakdownDocument = PlannerSchemas["ScoreBreakdownDocument"];
export type RollupSummaryDocument = PlannerSchemas["RollupSummaryDocument"];
export type WeeklyRollupDocument = PlannerSchemas["WeeklyRollupDocument"];
export type DailyRollupDocument = PlannerSchemas["DailyRollupDocument"];
export type ReoptSuggestionDto = PlannerSchemas["ReoptSuggestionDto"];
export type PlanReoptSuggestionDto = PlannerSchemas["PlanReoptSuggestionDto"];
export type ProposedReoptAssignmentsDocument =
  PlannerSchemas["ProposedReoptAssignmentsDocument"];
export type ProposedSlotChange = PlannerSchemas["ProposedSlotChange"];
export type FeasibilityCheckResultDto = PlannerSchemas["FeasibilityCheckResultDto"];
export type ConstraintConflictDto = PlannerSchemas["ConstraintConflictDto"];
export type ResolutionOptionDto = PlannerSchemas["ResolutionOptionDto"];
export type GeneratePlanRequest = PlannerSchemas["GeneratePlanRequest"];

/** Status-mark glyph set: the five contract slot states plus the derived
 *  affected-by-suggestion overlay (NOT a slot state — spec §3d). */
export type SlotMark = SlotState | "AFFECTED";

/* ---- generation flow state ----------------------------------------------------- */

export type GenerationStatus = "idle" | "generating" | "review";

/**
 * POST /plans/generate intent state, including the Idempotency-Key fake:
 * one key per user intent, persisted until a 2xx lands; re-submitting with
 * the same key serves the cached plan back (200 replay, spec §4b).
 */
export interface GenerationState {
  status: GenerationStatus;
  /** Target Monday (GeneratePlanRequest.weekStartDate). */
  weekStartDate: string;
  /** GeneratePlanRequest.forceRegenerateIfActive consent checkbox. */
  forceRegenerateIfActive: boolean;
  /** Current intent's Idempotency-Key; "Regenerate all" mints a new one. */
  idempotencyKey: string | null;
  /** Mock server replay cache: Idempotency-Key → planId already served. */
  served: Record<string, string>;
  resultPlanId: string | null;
  /** True when the last response was a 200 cached replay (vs 201 created). */
  replayed: boolean;
  /** Regeneration round — deterministically varies generated content. */
  round: number;
}

export interface PlannerState {
  /** Every generation the mock knows, across weeks (the plan store #1–#4). */
  plans: PlanDto[];
  /** PENDING re-opt suggestions (#12 list — no proposedAssignments pre-accept). */
  suggestions: ReoptSuggestionDto[];
  /**
   * Mock server side: suggestionId → diff revealed by the accept response.
   * The list DTO deliberately omits this (contract gap, spec §8 Q2).
   */
  proposedBySuggestion: Record<string, ProposedReoptAssignmentsDocument>;
  /** Accept response (#13) held for the post-accept review panel. */
  lastReoptOutcome: { dto: PlanReoptSuggestionDto; newPlanId: string } | null;
  /** weekStartDate → feasibility check result (#5). */
  feasibility: Record<string, FeasibilityCheckResultDto>;
  /**
   * Slot the mock "server" has already advanced on another device — first
   * action on it returns the 409 + re-fetch demo (spec §8 status map).
   */
  racedSlot: { slotId: string; serverState: SlotState } | null;
  generation: GenerationState;
}

/* ---- recipes: backend DTO mirrors ---------------------------------------------
 * The recipe slices mirror the real contract (design/frontend/pages/recipes.md,
 * recipe-detail.md §2) exactly like the nutrition + planner slices — re-exported
 * from the generated OpenAPI types so the mock validates production shapes.
 */

type RecipeSchemas = components["schemas"];

export type Catalogue = RecipeSchemas["Catalogue"];
export type DataQuality = RecipeSchemas["DataQuality"];
export type NutritionStatus = RecipeSchemas["NutritionStatus"];
export type VersionTrigger = RecipeSchemas["VersionTrigger"];

export type RecipeDto = RecipeSchemas["RecipeDto"];
export type RecipeVersionDto = RecipeSchemas["RecipeVersionDto"];
export type RecipeBranchDto = RecipeSchemas["RecipeBranchDto"];
export type IngredientDto = RecipeSchemas["IngredientDto"];
export type MethodStepDto = RecipeSchemas["MethodStepDto"];
export type RecipeMetadataDto = RecipeSchemas["RecipeMetadataDto"];
export type RecipeTagsDto = RecipeSchemas["RecipeTagsDto"];
export type RecipeDiffDto = RecipeSchemas["RecipeDiffDto"];
export type IngredientChangeDto = RecipeSchemas["IngredientChangeDto"];

export type RecipeSubstitutionDto = RecipeSchemas["RecipeSubstitutionDto"];
export type SubstitutionState = RecipeSchemas["SubstitutionState"];
export type SubstitutionReason = RecipeSchemas["SubstitutionReason"];
export type CreateSubstitutionRequest = RecipeSchemas["CreateSubstitutionRequest"];

export type RecipeRatingDto = RecipeSchemas["RecipeRatingDto"];
export type RecipeRatingSummaryDto = RecipeSchemas["RecipeRatingSummaryDto"];
export type CreateRatingRequest = RecipeSchemas["CreateRatingRequest"];

export type CreateRecipeRequest = RecipeSchemas["CreateRecipeRequest"];
export type CreateIngredientRequest = RecipeSchemas["CreateIngredientRequest"];
export type CreateMethodStepRequest = RecipeSchemas["CreateMethodStepRequest"];
export type CreateRecipeMetadataRequest =
  RecipeSchemas["CreateRecipeMetadataRequest"];
export type UpdateRecipeManualEditRequest =
  RecipeSchemas["UpdateRecipeManualEditRequest"];
export type CreateBranchRequest = RecipeSchemas["CreateBranchRequest"];
export type RevertToVersionRequest = RecipeSchemas["RevertToVersionRequest"];

export type RecipeImportPreview = RecipeSchemas["RecipeImportPreview"];
export type ConfirmImportRequest = RecipeSchemas["ConfirmImportRequest"];
export type RecipeImportDto = RecipeSchemas["RecipeImportDto"];
export type RecipeNutritionResultDto = RecipeSchemas["RecipeNutritionResultDto"];

/**
 * Server-side recipe data the page reads per-recipe (versions, substitutions,
 * ratings, provenance, recalc results). Keyed maps stand in for the per-recipe
 * GET endpoints; `StoreState.recipes` itself stands in for the MISSING library
 * list endpoint (recipes.md §8 Q1 — the headline backend gap).
 */
export interface RecipeDataState {
  /** recipeId → branchId → versions ascending by versionNumber (#6/#7). */
  versions: Record<string, Record<string, RecipeVersionDto[]>>;
  /** recipeId → substitution rows, all states (reads filter; PROPOSED rows
   *  are client-remembered only — recipe-detail.md §11 Q2). */
  substitutions: Record<string, RecipeSubstitutionDto[]>;
  /** recipeId → rating rows, newest first (#18–#23; summaries computed). */
  ratings: Record<string, RecipeRatingDto[]>;
  /** recipeId → import provenance; absent = manual recipe (the #15 404). */
  provenance: Record<string, RecipeImportDto>;
  /**
   * versionId → recalculate result (n1). The ONLY contract source of
   * per-serving numbers — RecipeVersionDto carries no nutritionPerServing
   * (recipe-detail.md §11 Q1, headline gap). Populated by Recalculate only.
   */
  nutritionByVersion: Record<string, RecipeNutritionResultDto>;
}

/* ---- grocery: backend DTO mirrors -----------------------------------------------
 * Contract shapes throughout (design/frontend/pages/groceries.md §2) —
 * re-exported from the generated OpenAPI types so the mock validates the
 * production field names, exactly like the nutrition/planner/recipe slices.
 */

type GrocerySchemas = components["schemas"];

export type ShoppingListDto = GrocerySchemas["ShoppingListDto"];
export type ShoppingListLineDto = GrocerySchemas["ShoppingListLineDto"];
export type ShoppingListLineType = GrocerySchemas["ShoppingListLineType"];
export type LineFulfilmentStatus = GrocerySchemas["LineFulfilmentStatus"];
export type BoughtVia = NonNullable<ShoppingListLineDto["boughtVia"]>;
export type ExportFormat = GrocerySchemas["ExportFormat"];
export type ShoppingListExportDto = GrocerySchemas["ShoppingListExportDto"];
export type RecalculateShoppingListRequest =
  GrocerySchemas["RecalculateShoppingListRequest"];
export type MarkBoughtRequest = GrocerySchemas["MarkBoughtRequest"];
export type BoughtUnit = MarkBoughtRequest["boughtUnit"];
export type BulkMarkBoughtRequest = GrocerySchemas["BulkMarkBoughtRequest"];
export type MarkBoughtResultDto = GrocerySchemas["MarkBoughtResultDto"];

export type GroceryOrderDto = GrocerySchemas["GroceryOrderDto"];
export type GroceryOrderLineDto = GrocerySchemas["GroceryOrderLineDto"];
export type GroceryOrderStatus = GrocerySchemas["GroceryOrderStatus"];
export type OrderLineStatus = GrocerySchemas["OrderLineStatus"];
export type GrocerySubstitutionProposalDto =
  GrocerySchemas["GrocerySubstitutionProposalDto"];
export type SubstitutionProposalStatus =
  GrocerySchemas["SubstitutionProposalStatus"];
export type ResolveSubstitutionRequest =
  GrocerySchemas["ResolveSubstitutionRequest"];
export type CancelOrderRequest = GrocerySchemas["CancelOrderRequest"];
export type GroceryProviderStateDto = GrocerySchemas["GroceryProviderStateDto"];

export type PriceSource = GrocerySchemas["PriceSource"];
export type PriceAggregateDto = GrocerySchemas["PriceAggregateDto"];
export type PriceObservationDto = GrocerySchemas["PriceObservationDto"];
export type RecordManualPriceRequest =
  GrocerySchemas["RecordManualPriceRequest"];
export type RefreshPricesRequest = GrocerySchemas["RefreshPricesRequest"];
export type RefreshPricesResultDto = GrocerySchemas["RefreshPricesResultDto"];

export interface GroceryState {
  /**
   * Every generation the mock knows, newest first; the current list (#1) is
   * the first non-superseded row for the active plan. Older rows play the
   * history drawer (#3) with retro mark-bought enabled.
   */
  lists: ShoppingListDto[];
  /** Orders newest first (#9); ARCHIVED rows excluded from the default view. */
  orders: GroceryOrderDto[];
  /**
   * orderId → ALL proposals (#18) including resolved ones; the order DTO's
   * outstandingProposals[] carries only the unresolved subset.
   */
  proposalsByOrder: Record<string, GrocerySubstitutionProposalDto[]>;
  /** GET providers/{key} (#20); null plays the 404 connect-CTA empty state. */
  providerState: GroceryProviderStateDto | null;
  /**
   * ingredientMappingKey → per-store aggregate rows (#21/#22). store=null is
   * the cross-store blend; absence of a key plays the 404 "no data yet".
   */
  aggregates: Record<string, PriceAggregateDto[]>;
  /** Price observations, newest first (#23/#24). */
  observations: PriceObservationDto[];
}

/* ---- pantry: backend DTO mirrors --------------------------------------------------
 * Provisions contract shapes throughout (design/frontend/pages/pantry.md §2).
 */

export type StorageLocation = GrocerySchemas["StorageLocation"];
export type TrackingMode = GrocerySchemas["TrackingMode"];
export type StapleStatus = GrocerySchemas["StapleStatus"];
export type ItemSource = GrocerySchemas["ItemSource"];
export type ItemLifecycleStatus = GrocerySchemas["ItemLifecycleStatus"];
export type DefrostMethod = GrocerySchemas["DefrostMethod"];
export type InventoryItemDto = GrocerySchemas["InventoryItemDto"];
export type FreezerExtension = NonNullable<InventoryItemDto["freezerExtension"]>;
export type CreateInventoryItemRequest =
  GrocerySchemas["CreateInventoryItemRequest"];
export type UpdateInventoryItemRequest =
  GrocerySchemas["UpdateInventoryItemRequest"];
export type AdjustInventoryQuantityRequest =
  GrocerySchemas["AdjustInventoryQuantityRequest"];
export type InventoryAuditEntryDto = GrocerySchemas["InventoryAuditEntryDto"];
export type AuditActor = GrocerySchemas["AuditActor"];
export type MealConsumptionCommand = GrocerySchemas["MealConsumptionCommand"];
export type InventoryDeductionResultDto =
  GrocerySchemas["InventoryDeductionResultDto"];

export type WasteEntryDto = GrocerySchemas["WasteEntryDto"];
export type WasteReason = GrocerySchemas["WasteReason"];
export type WasteSummaryDto = GrocerySchemas["WasteSummaryDto"];
export type TopWastedItemDto = GrocerySchemas["TopWastedItemDto"];
export type LogWasteRequest = GrocerySchemas["LogWasteRequest"];

export type EquipmentDto = GrocerySchemas["EquipmentDto"];
export type UpsertEquipmentRequest = GrocerySchemas["UpsertEquipmentRequest"];

export type BudgetDto = GrocerySchemas["BudgetDto"];
export type UpdateBudgetRequest = GrocerySchemas["UpdateBudgetRequest"];
export type PriceSensitivity = GrocerySchemas["PriceSensitivity"];

export type SupplierProductDto = GrocerySchemas["SupplierProductDto"];
export type SubstitutionRecordDto = GrocerySchemas["SubstitutionRecordDto"];

export interface PantryState {
  /**
   * Every inventory row the mock knows, ALL lifecycle states; the list read
   * (#1) filters to ACTIVE (the contract returns ACTIVE only — pantry.md §9
   * Q2: spoiled/exhausted/wasted rows leave the list on mutation).
   */
  items: InventoryItemDto[];
  /** itemId → audit entries newest first (#9, detail-drawer history tab). */
  auditByItem: Record<string, InventoryAuditEntryDto[]>;
  /** Waste entries newest first (#12); immutable — corrections append. */
  waste: WasteEntryDto[];
  /** Equipment rows (#14). */
  equipment: EquipmentDto[];
  /** Budget row (#17); null plays the GET 404 set-budget empty state. */
  budget: BudgetDto | null;
  /** Supplier-product price-book cache rows (#19), lastChecked DESC. */
  supplierProducts: SupplierProductDto[];
}

/* ---- notifications --------------------------------------------------------- */

export type NotificationKind =
  | "plan"
  | "recipe"
  | "grocery"
  | "order"
  | "pantry"
  | "expiry"
  | "ai";

export interface AppNotification {
  id: string;
  kind: NotificationKind;
  title: string;
  time: string;
  read: boolean;
}

/* ---- adaptation -----------------------------------------------------------------
 * Contract shapes for GET /adaptation/pending-changes (+ detail, + per-recipe
 * history). Today shows row 1; Activity owns the top-3; recipe-detail consumes
 * the per-recipe slice (recipe-detail.md §10, a1–a5).
 */

export type PendingChangeListItemDto =
  components["schemas"]["PendingChangeListItemDto"];
export type PendingChangeDto = components["schemas"]["PendingChangeDto"];
export type PendingChangeStatus = components["schemas"]["PendingChangeStatus"];
export type ChangeDimension = PendingChangeListItemDto["changeDimension"];

export interface AdaptationState {
  /** Ranked pending recipe changes, server-ordered best-first (a1). */
  pendingChanges: PendingChangeListItemDto[];
  /**
   * Mock server side: id → full detail (a2). Accept requires the detail's
   * optimisticVersion — expand-then-accept, two calls (spec §11 Q5).
   */
  detailById: Record<string, PendingChangeDto>;
  /** recipeId → resolved past proposals, newest first (a5 history). */
  historyByRecipe: Record<string, PendingChangeDto[]>;
}

/* ---- toasts (transient UI, not a DTO) ------------------------------------------ */

export interface ToastItem {
  id: number;
  text: string;
  /** warn = 409/422-style guard messages (amber). */
  tone: "info" | "warn";
}

/* ---- nutrition: backend DTO mirrors ------------------------------------------
 * Re-exported from the generated OpenAPI types so the mock store carries the
 * exact production field names (spec: design/frontend/pages/nutrition.md §2).
 */

type Schemas = components["schemas"];

export type MealSlot = Schemas["MealSlot"];
export type ActivityLevel = Schemas["ActivityLevel"];
export type Goal = Schemas["Goal"];
export type EnforcementDirection = Schemas["EnforcementDirection"];
export type IntakeSlotStatus = Schemas["IntakeSlotStatus"];
export type IntakeSource = Schemas["IntakeSource"];

export type PlannedIntakeDto = Schemas["PlannedIntakeDto"];
export type ActualIntakeDto = Schemas["ActualIntakeDto"];
export type IntakeSlotDto = Schemas["IntakeSlotDto"];
export type IntakeSnackDto = Schemas["IntakeSnackDto"];
export type IntakeDayDto = Schemas["IntakeDayDto"];
export type IntakeEntryDto = Schemas["IntakeEntryDto"];
export type LogSnackRequest = Schemas["LogSnackRequest"];

export type DailyAggregateDto = Schemas["DailyAggregateDto"];
export type MacroAggregateDto = Schemas["MacroAggregateDto"];
export type WeeklyAggregateDto = Schemas["WeeklyAggregateDto"];

export type CalorieTargetDto = Schemas["CalorieTargetDto"];
export type MacroTargetDto = Schemas["MacroTargetDto"];
export type MicroTargetDto = Schemas["MicroTargetDto"];
export type PerMealDistributionDto = Schemas["PerMealDistributionDto"];
export type ActivityAdjustmentDto = Schemas["ActivityAdjustmentDto"];
export type TargetsDto = Schemas["TargetsDto"];
export type UpdateTargetsRequest = Schemas["UpdateTargetsRequest"];
export type DailyActivityDto = Schemas["DailyActivityDto"];

export type FoodMoodEntryDto = Schemas["FoodMoodEntryDto"];

export type HealthDirectiveDto = Schemas["HealthDirectiveDto"];
export type DirectiveStatus = Schemas["DirectiveStatus"];
export type DirectiveType = Schemas["DirectiveType"];
export type DirectiveInstructionDocument = Schemas["DirectiveInstructionDocument"];
export type DirectivePhaseDto = Schemas["DirectivePhaseDto"];
export type SafetyFindingDto = Schemas["SafetyFindingDto"];
export type SafetyGateVerdict = Schemas["SafetyGateVerdict"];
/** Shape sent on accept-with-modification (AcceptDirectiveRequest.userModification). */
export type DirectiveUserModification = NonNullable<
  Schemas["AcceptDirectiveRequest"]["userModification"]
>;

export type IngredientNutritionDto = Schemas["IngredientNutritionDto"];
export type IngredientNutritionDocument = Schemas["IngredientNutritionDocument"];
export type IngredientMappingSource = Schemas["IngredientMappingSource"];

export interface NutritionState {
  /** ISO date → intake day. The seed covers the plan week (Mon–Sun). */
  intakeDays: Record<string, IntakeDayDto>;
  /**
   * Slot ids currently inside the fake AI-parse window after an override
   * (transient UI state — not part of any DTO).
   */
  parsingSlotIds: string[];
  /** ISO date → daily activity entry (PUT targets/activity/{date}). */
  dailyActivity: Record<string, DailyActivityDto>;
  /** Food & mood journal, newest first (all dates). */
  journal: FoodMoodEntryDto[];
  /** Health directives inbox, newest first. */
  directives: HealthDirectiveDto[];
  /** Ingredient nutrition cache rows (lookup assist + data-quality queue). */
  ingredientCache: IngredientNutritionDto[];
}

/* ---- preferences: backend DTO mirrors ---------------------------------------------
 * Contract shapes throughout (design/frontend/pages/preferences.md §2) —
 * re-exported from the generated OpenAPI types so the mock validates the
 * production field names, exactly like the nutrition/planner/recipe slices.
 */

export type TasteVectorStatus = Schemas["TasteVectorStatus"];
export type TasteProfileTrigger = Schemas["TasteProfileTrigger"];
export type TasteProfileChangeType = Schemas["TasteProfileChangeType"];
export type IngredientPreferenceSource = Schemas["IngredientPreferenceSource"];
export type TasteProfileDocument = Schemas["TasteProfileDocument"];
export type TasteProfileDto = Schemas["TasteProfileDto"];
export type TasteProfileVersionDto = Schemas["TasteProfileVersionDto"];
export type TasteProfileAuditEntryDto = Schemas["TasteProfileAuditEntryDto"];
export type UpdateTasteProfileRequest = Schemas["UpdateTasteProfileRequest"];
export type RollbackTasteProfileRequest = Schemas["RollbackTasteProfileRequest"];
export type IngredientPreference = Schemas["IngredientPreference"];
export type TrendingIngredient = Schemas["TrendingIngredient"];
export type RecipeRecommendation = Schemas["RecipeRecommendation"];
export type ActiveExperiment = Schemas["ActiveExperiment"];
export type SoftIntolerance = Schemas["SoftIntolerance"];

export type HardConstraintsDto = Schemas["HardConstraintsDto"];
export type UpdateHardConstraintsRequest = Schemas["UpdateHardConstraintsRequest"];
export type HardConstraintsAuditEntryDto = Schemas["HardConstraintsAuditEntryDto"];
export type DietaryIdentityDto = Schemas["DietaryIdentityDto"];
export type DietaryIdentityExceptionDto = Schemas["DietaryIdentityExceptionDto"];
export type HardIntoleranceDto = Schemas["HardIntoleranceDto"];
export type AgeRestrictionDto = Schemas["AgeRestrictionDto"];
/** The GAP-04 409 problem body — drives the removal interstitial (§4b). */
export type Tier1RemovalConfirmationProblem =
  Schemas["Tier1RemovalConfirmationProblem"];
export type RemovedTier1Constraint = Schemas["RemovedTier1Constraint"];
export type Tier1Category = Schemas["Tier1Category"];

export type LifestyleConfigDto = Schemas["LifestyleConfigDto"];
export type PreferenceLifestyleConfigDocument =
  Schemas["PreferenceLifestyleConfigDocument"];
export type UpdateLifestyleConfigRequest = Schemas["UpdateLifestyleConfigRequest"];
export type LifestyleConfigAuditEntryDto = Schemas["LifestyleConfigAuditEntryDto"];

export type PreferenceArchiveEntryDto = Schemas["PreferenceArchiveEntryDto"];
export type ArchivedReason = PreferenceArchiveEntryDto["archivedReason"];

export interface PreferencesState {
  /** GET /preferences/taste-profile (#1); null plays the 404 onboarding empty state. */
  tasteProfile: TasteProfileDto | null;
  /** Version snapshots newest first (#5/#6 drawer). */
  versions: TasteProfileVersionDto[];
  /** Taste-profile audit log newest first (#7). */
  tasteAudit: TasteProfileAuditEntryDto[];
  /** Refresh-now poll state — 202 then poll #1; no completion signal (spec §8 Q2). */
  refreshing: boolean;
  /** GET /preferences/hard-constraints (#8); null plays the 404 empty state. */
  hardConstraints: HardConstraintsDto | null;
  hardAudit: HardConstraintsAuditEntryDto[];
  /** GET /preferences/lifestyle-config (#11); null plays the 404 empty state. */
  lifestyle: LifestyleConfigDto | null;
  lifestyleAudit: LifestyleConfigAuditEntryDto[];
  /** Archive rows newest first (#15); active-count (#16) = rePromotedAt == null. */
  archive: PreferenceArchiveEntryDto[];
}

/* ---- activity / feedback: backend DTO mirrors ---------------------------------------
 * Contract shapes throughout (design/frontend/pages/activity.md §2). Routing
 * tiers are SERVER-decided — render from RoutingDecisionDto.decision, never
 * re-derive from confidence except as a fallback (spec §4b).
 */

/** ✓ olive ≥0.8 routed · ? amber 0.5–0.8 check me · … terra <0.5 needs you. */
export type ConfidenceTier = "high" | "mid" | "low";

export type Destination = Schemas["Destination"];
export type RoutingDecision = Schemas["RoutingDecision"];
export type RoutingStatus = Schemas["RoutingStatus"];
export type SubmissionStatus = Schemas["SubmissionStatus"];
export type FeedbackScreen = Schemas["Screen"];
export type UiContextDto = Schemas["UiContextDto"];
export type FeedbackEntryDto = Schemas["FeedbackEntryDto"];
export type RoutingDecisionDto = Schemas["RoutingDecisionDto"];
export type SubmitFeedbackRequest = Schemas["SubmitFeedbackRequest"];
export type SubmitFeedbackResponse = Schemas["SubmitFeedbackResponse"];
export type ClarificationQueryDto = Schemas["ClarificationQueryDto"];
export type ClarificationOptionDto = Schemas["ClarificationOptionDto"];
export type ClarificationStatus = Schemas["ClarificationStatus"];
export type AnswerClarificationRequest = Schemas["AnswerClarificationRequest"];
export type MisclassificationCorrectionDto =
  Schemas["MisclassificationCorrectionDto"];
export type CorrectionReplayStatus = Schemas["CorrectionReplayStatus"];

export interface ActivityState {
  /** GET /feedback page rows, newest first (#6). */
  feedback: FeedbackEntryDto[];
  /** Clarification queries, ALL statuses; the inbox filters (#10). */
  clarifications: ClarificationQueryDto[];
  /** Corrections log rows, newest first (#9). */
  corrections: MisclassificationCorrectionDto[];
  /**
   * Transient UI state (not a DTO): pre-filled text for the global feedback
   * modal — the 410-expired-clarification "re-submit" CTA (spec §5b).
   */
  composePrefill: string | null;
}

/* ---- notification preferences ----------------------------------------------------- */

export interface NotificationPrefs {
  /** Muted kinds drop out of the bell dropdown + rail badge. */
  muted: NotificationKind[];
  quietStart: string;
  quietEnd: string;
}

/* ---- household / settings ----------------------------------------------------------- */

export type MemberRole = "owner" | "adult" | "child";

export interface HouseholdMember {
  id: string;
  name: string;
  role: MemberRole;
  /** Per-member identity dot (CSS colour value). */
  color: string;
}

export interface PendingInvite {
  email: string;
  sent: string;
}

export interface SlotConfigEntry {
  slot: MealSlotKey;
  time: string;
  /** Shared household meal vs per-person. */
  shared: boolean;
}

export interface DayTypeSlots {
  dayType: string;
  slots: SlotConfigEntry[];
}

export interface HouseholdState {
  name: string;
  members: HouseholdMember[];
  invites: PendingInvite[];
  slotConfig: DayTypeSlots[];
  /** Account email (display only). */
  email: string;
}

/* ---- discovery: backend DTO mirrors ---------------------------------------------
 * Contract shapes throughout (design/frontend/pages/discover.md §2): the job
 * lifecycle is QUEUED → RUNNING → SUCCEEDED | FAILED | PARTIAL; results are
 * scrape-log SUCCESS rows joined to already-persisted system-catalogue recipes.
 */

export type DiscoveryJobDto = components["schemas"]["DiscoveryJobDto"];
export type DiscoveryJobStatus = components["schemas"]["DiscoveryJobStatus"];
export type DiscoveryJobTrigger = components["schemas"]["DiscoveryJobTrigger"];
export type DiscoveryConstraints = components["schemas"]["DiscoveryConstraints"];
export type StartDiscoveryJobRequest =
  components["schemas"]["StartDiscoveryJobRequest"];
export type DiscoveryScrapeLogEntryDto =
  components["schemas"]["DiscoveryScrapeLogEntryDto"];
export type ScrapeOutcome = components["schemas"]["ScrapeOutcome"];
export type ScrapeSkipReason = components["schemas"]["ScrapeSkipReason"];
export type RobotsTxtOutcome = components["schemas"]["RobotsTxtOutcome"];
export type DiscoverySourceDto = components["schemas"]["DiscoverySourceDto"];
export type DiscoverySourceKind = components["schemas"]["DiscoverySourceKind"];

export interface DiscoveryState {
  /** Every job the mock knows, queued-at descending (#5 history page). */
  jobs: DiscoveryJobDto[];
  /** jobId → scrape-log rows in occurrence order (#4; written eagerly). */
  scrapeLog: Record<string, DiscoveryScrapeLogEntryDto[]>;
  /** Source registry (#6) — read-only in v1 (user disable unshipped, §9 Q4). */
  sources: DiscoverySourceDto[];
  /** Job whose card is mounted (start panel / history row click). */
  openJobId: string | null;
  /** Scrape-row ids locally dismissed via Skip — no contract call (§9 Q5). */
  skippedRowIds: string[];
  /** RUNNING-cancel flag: stops the mock runner between candidates (§4). */
  cancelRequested: string | null;
}

/* ---- root ------------------------------------------------------------------ */

export interface StoreState {
  planner: PlannerState;
  adaptation: AdaptationState;
  /**
   * Library rows on the production DTO. NOTE: the shipped contract has NO
   * GET /recipes list endpoint (recipes.md §8 Q1) — this array stands in for
   * it and the library page footnotes the gap.
   */
  recipes: RecipeDto[];
  recipeData: RecipeDataState;
  grocery: GroceryState;
  pantry: PantryState;
  notifications: AppNotification[];
  nutrition: NutritionState;
  targets: TargetsDto;
  preferences: PreferencesState;
  activity: ActivityState;
  notificationPrefs: NotificationPrefs;
  household: HouseholdState;
  discovery: DiscoveryState;
  /** Transient toast stack (409-guard + confirmation messages). */
  toasts: ToastItem[];
}
