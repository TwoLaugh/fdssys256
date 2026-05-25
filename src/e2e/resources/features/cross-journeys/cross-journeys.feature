@cross @cross-journeys
Feature: Cross-domain journeys — the integration capstone (XJ-01..06)
  The multi-domain user journeys that thread through several domains and assert
  the hand-offs between them hold (see e2e/pathways/cross-journeys.md). Each XJ
  is composed entirely of already-catalogued per-domain pathways; this feature
  builds the BUILDABLE subset of every journey by chaining the existing
  per-domain steps (which share ONE ScenarioContext via PicoContainer) plus a
  couple of distinctly-phrased bridging steps (CrossJourneySteps) where two
  domains use different namespaced context keys. Legs that need an unbuilt
  surface (a real recipe POOL for plan slots, grocery provider automation, a
  live import URL, the soft-preference merge / split) are @pending with the
  blocking leg named — the catalogue is exhaustive; the test set is the
  buildable subset.

  Each scenario mints its OWN fresh user/household (D5 self-contained data) and
  asserts only on THIS user's / household's state (self-scoped) — never global
  counts — so the feature runs in both clean and soak mode.

  # ============================================================================
  # XJ-01 — Recipe -> Nutrition: edit -> recalculation -> new version (logged)
  # ============================================================================

  @xj01
  # XJ-01 buildable spine (RCP-19 -> RCP-20 -> NUT-31 -> RCP-15): the Recipe<->Nutrition
  # integration backbone. A user-catalogue recipe is edited (a new version on the current
  # branch with the MANUAL_EDIT trigger + a change reason), the edit publishes
  # RecipeEvolvedEvent, and the new version carries a recalculated nutrition status — the
  # cross-module Recipe -> Nutrition recalc relay. Substitutes a manual create for the
  # @pending web-import leg (RCP-03) so the recalc hand-off is asserted green today.
  Scenario: A recipe edit recalculates nutrition and records a new version
    Given a fresh registered and logged-in user
    When they create a manual recipe
    Then the recipe is created in their user catalogue at version 1
    And the imported recipe has internally derived nutrition status
    When they manually edit that recipe
    Then a new version 2 is created with the manual-edit trigger and a change reason
    And the recipe's current version body reflects the edit and a recalculated nutrition status

  @xj01 @pending
  # XJ-01 full happy end-to-end. BLOCKING LEG: step 1 RCP-03 (web URL import) fetches a LIVE
  # web page and runs deterministic JSON-LD/microdata extraction (the URL fetch is real, NOT
  # the AI double), so a stable whitelisted recipe URL fixture must be provisioned in CI before
  # the import + downstream USDA-derive (NUT-26) -> edit -> recalc chain can run green. Un-pend
  # once a recipe-URL fixture lands (also tracked by cross/xj01_web_import_to_recalc.feature).
  Scenario: Import a web recipe, derive USDA nutrition, edit an ingredient, and recalculate
    Given a fresh registered and logged-in user
    When they import a recipe from a reachable recipe URL
    Then the recipe is imported into their user catalogue with imported data quality
    And the imported recipe has internally derived nutrition status
    When they manually edit that recipe
    Then a new version 2 is created with the manual-edit trigger and a change reason
    And the recipe's current version body reflects the edit and a recalculated nutrition status

  # ============================================================================
  # XJ-02 — Feedback learning loop: one input classified -> routed -> applied
  # ============================================================================

  @xj02
  # XJ-02 buildable spine (FEED-07 -> route -> applied): one free-text feedback input is
  # classified by the cheap AI and routed to a destination that APPLIES it, then the routed
  # entry is observable on a self-scoped read — the Feedback classifier -> destination
  # update-service relay. Uses the PROVISIONS MARK_DEPLETED no-op (idempotent APPLIED for a
  # fresh user) as the clean cross-domain APPLIED leg.
  Scenario: A feedback input is classified and routed to a destination that applies it
    Given a fresh registered and logged-in user
    And the AI will classify the next feedback to provisions at high confidence
    When they submit feedback "I'm out of soy sauce and it was really expensive"
    Then the feedback submission is accepted for processing
    And the feedback entry eventually reaches a routed state for this user
    And the feedback entry has a routing decision to provisions for this user

  @xj02 @pending
  # XJ-02 full fan-out + reverters. BLOCKING LEG: the four-destination split (RCP-32 || PREF-16
  # || NUT-38 || PROV-32) needs a recipe in the user catalogue routed to a PENDING_APPROVAL
  # recipe route AND seeded preference/nutrition aggregates so all four routes APPLY (a fresh
  # user's PREFERENCE route is FAILED not APPLIED, and there is no HTTP path for a recipe-feedback
  # route); the misclassification reverter (FEED-19 -> FEED-20 -> FEED-21) then needs that
  # correctable recipe route to undo. Un-pend once a recipe-route + multi-aggregate fixture lands.
  Scenario: One feedback fans out to four destinations and a correction fires the reverters
    Given a fresh registered and logged-in user
    And the user has a seeded preference-routed feedback entry
    When they correct that route to nutrition
    Then the correction is recorded alongside the original route for this user

  # ============================================================================
  # XJ-03 — Week-planner critical path: generate -> accept -> nutrition actuals
  # ============================================================================

  @xj03
  # XJ-03 buildable spine (PLAN-01/07/09 -> PLAN-13 -> NUT-19 intake touch): the planner
  # composes a plan (Stage A beam-search -> Stage C LLM pick -> Phase 2, AI primed) and the
  # user accepts it (-> ACTIVE; PlanAcceptedEvent), then logs same-day intake — the Planner
  # output + Nutrition Logger cross-module touch. The plan slots are empty (no recipe pool),
  # so the cook/eat actuals + re-opt cascade are deferred to the @pending full path below.
  Scenario: A user generates and accepts a week plan, then logs same-day intake
    Given a fresh registered and logged-in user
    And the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household
    When they log a standalone snack for today
    Then the snack is recorded on today's intake for this user

  @xj03 @pending
  # XJ-03 full critical path. BLOCKING LEG: PLAN-18 (mark slots cooking -> cooked -> eaten) and
  # the NUT-22 -> PLAN-22 -> PLAN-21 mid-week re-opt cascade assert on SCHEDULED-RECIPE slots,
  # whose shape needs a real recipe POOL (NoOpRecipePoolSource is wired; no catalogue-wide recipe
  # -search surface exists), so a generated plan has no slots to transition. Un-pend when an HTTP
  # recipe-pool/recipe-search surface lands and a plan generates with real slots.
  Scenario: The week plan is cooked, eaten, and mid-week re-optimised from nutrition actuals
    Given a fresh registered and logged-in user
    And the user has a household
    And the AI will pick the recommended plan candidate
    When they generate a plan for a week
    Then the generated plan has scheduled recipes in its slots for this household
    When they accept that plan
    Then the plan becomes active for this household
    When they mark the first slot of that plan cooking
    Then the slot is marked cooking for this household

  # ============================================================================
  # XJ-04 — Household merge -> shared plan (union applied at generate time)
  # ============================================================================

  @xj04
  # XJ-04 buildable spine (HH-01 -> HH-02 -> shared dinner default -> HH-19 union -> generate):
  # a two-member household is assembled (primary + a second member added), the default dinner
  # slot is shared with both eaters, and a plan is generated for the household — the planner
  # computes the deterministic hard-constraint UNION over both members (HH-19) at generate time.
  # This is the satisfiable-union case (no split). Bridges Household -> Planner via
  # CrossJourneySteps (the household id is stashed under the Household-domain key).
  Scenario: A two-member household generates one shared plan filtered by the member union
    Given a fresh registered and logged-in user
    And the user has created a household
    And a second user account exists
    And the primary has added the second user as a member
    Then the household roster includes the second member for this household
    When they generate a shared plan for that household
    Then a shared generated plan is created for that household

  @xj04 @pending
  # XJ-04 irreconcilable-union split. BLOCKING LEG: PLAN-34 (split the shared slot into per-member
  # meals when the union is irreconcilable) needs the soft-preference MERGE algorithm + a shared-
  # slot recipe composition surface — the merge is explicitly deferred to a non-existent Household
  # Model design (the central XJ-04 HLD-GAP X8), and slot composition needs a real recipe pool.
  # Un-pend when the soft-merge + shared-slot composition + recipe-pool surfaces land.
  Scenario: An irreconcilable member union splits the shared slot into per-member meals
    Given a fresh registered and logged-in user
    And the user has created a household
    And a second user account exists
    And the primary has added the second user as a member
    When they generate a shared plan for that household
    Then the generated plan has scheduled recipes in its slots for this household

  # ============================================================================
  # XJ-05 — Grocery loop (manual-only variant): plan -> order -> inventory -> read
  # ============================================================================

  @xj05
  # XJ-05 manual-only buildable variant (PLAN-13 -> GROC-08 manual fulfilment -> PROV-03 inventory
  # write -> planner-facing read): an accepted plan, then a confirmed grocery order is fulfilled
  # into Provisions inventory (the addToInventory write every fulfilment path makes), and the
  # order is then visible in the planner-facing bundle — the Grocery -> Provisions -> Planner
  # relay. Bridges Provisions -> Planner read via CrossJourneySteps (non-empty bundle assertion).
  Scenario: An accepted plan's groceries are fulfilled into inventory and seen by the planner
    Given a fresh registered and logged-in user
    And the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household
    When they fulfil a confirmed grocery order
    Then the order lands in their inventory and price cache for this user
    And their planner bundle reflects the fulfilled grocery inventory for this user

  @xj05 @pending
  # XJ-05 full provider loop. BLOCKING LEG: the Tier-3 provider automation legs (GROC-15 quote ->
  # GROC-16/17 place/confirm -> GROC-19 substitution review -> GROC-18 reconcile) + the PROV-37
  # depleted-staple auto-add to the next list + NOTIF-09 expiry warning all target a grocery
  # module that is designed-but-not-yet-built (no shopping-list/order-lifecycle/substitution/price
  # HTTP surface exists). Un-pend when the grocery module lands.
  Scenario: A provider grocery order is quoted, placed, confirmed, and reconciled to inventory
    Given a fresh registered and logged-in user
    And the user has a household
    When they place a provider grocery order
    Then the provider order is awaiting user confirmation for this user
    When they resolve a delivery substitution proposal
    Then the substitution is applied to inventory for this user

  # ============================================================================
  # XJ-06 — Onboarding cold start: register -> seed models + household -> first plan
  # ============================================================================

  @xj06
  # XJ-06 buildable onboarding spine (AUTH-01/05 -> PREF-01 hard constraints -> NUT-01 targets ->
  # HH-01 household -> PLAN-06/07 -> PLAN-13): the "fresh user with a random handle" spine every
  # other journey folds into its Authenticated precondition. Register, set safety-critical hard
  # constraints, accept nutrition targets, create the household, then generate (cold-start, empty
  # pool still yields a plan) and accept it. The Recipe discovery/generation pre-step (RCP-10/09)
  # is exercised by the planner internally; with the empty pool the plan is produced regardless.
  Scenario: A new user onboards models and a household, then generates and accepts a first plan
    Given a fresh registered and logged-in user
    And the user has initialised hard constraints
    When they set their nutrition targets
    Then the targets are stored and returned for this user
    Given the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household

  @xj06 @pending
  # XJ-06 cold-start catalogue bootstrap. BLOCKING LEG: RCP-10/RCP-09 (the planner's discovery +
  # AI generation pre-step that fills the system catalogue up to 50 USDA-mapped recipes when below
  # the cold-start minimum) needs a real Recipe discovery/generation surface + recipe pool to
  # produce a plan with SCHEDULED recipes in its slots; with NoOpRecipePoolSource the plan has no
  # slots to assert against. Un-pend when the discovery/generation + recipe-pool surfaces land.
  Scenario: Cold start bootstraps the catalogue so the first plan has scheduled recipes
    Given a fresh registered and logged-in user
    And the user has initialised hard constraints
    Given the user has a household
    And the AI will pick the recommended plan candidate
    When they generate a plan for a week
    Then the generated plan has scheduled recipes in its slots for this household
