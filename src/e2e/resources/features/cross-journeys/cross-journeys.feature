@cross @cross-journeys
Feature: Cross-domain journeys — the integration capstone (XJ-01..06)
  The multi-domain user journeys that thread through several domains and assert
  the hand-offs between them hold (see e2e/pathways/cross-journeys.md). Each XJ
  is composed entirely of already-catalogued per-domain pathways; this feature
  builds the BUILDABLE subset of every journey by chaining the existing
  per-domain steps (which share ONE ScenarioContext via PicoContainer) plus a
  couple of distinctly-phrased bridging steps (CrossJourneySteps) where two
  domains use different namespaced context keys. Legs that need an unbuilt
  surface (a real recipe POOL for plan slots, grocery provider automation, the
  soft-preference merge / split) are @pending with the blocking leg named — the
  catalogue is exhaustive; the test set is the buildable subset.

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

  @xj01
  # XJ-01 full happy end-to-end (now GREEN). Step 1 RCP-03 (web URL import) fetches the app's OWN
  # hermetic fixture page (E2eRecipeFixtureController, e2e-profile only) over a REAL loopback HTTP
  # GET and runs the REAL deterministic JSON-LD extraction (the URL fetch is real, NOT the AI
  # double). The fixture's realistic whole-food ingredients feed the downstream USDA-derive
  # (NUT-26); the edit then triggers the Recipe -> Nutrition recalc relay and a new version. Also
  # tracked by cross/xj01_web_import_to_recalc.feature.
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

  @xj02
  # XJ-02 full fan-out + reverter (now GREEN). ONE free-text feedback the cheap AI classifies into
  # all four destinations (RCP-32 || PREF-16 || NUT-38 || PROV-32 — the @Size(max = 4) universe),
  # every classification >= 0.8 so each AUTO_ROUTES. The fixtures make all four routes non-FAILED:
  # a seeded taste profile (PREFERENCE empty-delta no-op APPLIES instead of 404-FAILING for a fresh
  # user), nutrition targets (NUTRITION protein_target_g +5% APPLIES), the PROVISIONS MARK_DEPLETED
  # no-op (APPLIED), and a real catalogue recipe so the RECIPE route enqueues a synchronous
  # adaptation FEEDBACK job that creates a PendingChange -> AWAITING_USER_APPROVAL (a propose/approve
  # outcome, non-failed but never APPLIED). All non-failed => the entry reconciles to ROUTED. Then
  # the misclassification reverter (FEED-19 -> FEED-20 -> FEED-21): correcting the RECIPE route away
  # fires the REAL RecipeFeedbackReverterImpl, which cancels the pending adaptation, and the
  # correction is recorded in the ground-truth audit. The adaptation Stage-C AI is not primed — the
  # e2e stub's built-in NO_CHANGE RECIPE_ADAPTATION default clears both validation gates and a
  # PENDING_CHANGE job stores the pending change regardless of the NO_CHANGE classification.
  Scenario: One feedback fans out to four destinations and a correction fires the recipe reverter
    Given a fresh registered and logged-in user
    And the user has an initialised taste profile
    When they set their nutrition targets
    Then the targets are stored and returned for this user
    When they create a manual recipe
    Then the recipe is created in their user catalogue at version 1
    And the AI will classify the next feedback to all four destinations
    When they submit feedback "out of soy sauce, more protein, love prawns, that recipe needed salt"
    Then the feedback submission is accepted for processing
    And the feedback entry eventually reaches a routed state for this user
    And the feedback entry has four routed destinations for this user
    When they correct the recipe route to nutrition
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

  @xj03
  # XJ-03 cook-leg critical path (now GREEN): the seeded catalogue (≥3 plannable recipes per
  # default-household slot kind, created over the real POST /api/v1/recipes path) gives Stage A
  # candidates, so generate yields a plan with SCHEDULED-RECIPE slots — accept it (-> ACTIVE) and
  # mark the first slot cooking (PLAN-18 PLANNED -> COOKING). This is the cook portion of the full
  # critical path.
  #
  # SCOPED DOWN: the mid-week re-optimisation tail (NUT-22 -> PLAN-22 -> PLAN-21: log diverging
  # nutrition actuals -> materiality filter raises a re-opt suggestion -> accept it into a fresh
  # generation) is NOT exercised here. That cascade is driven by asynchronous event listeners with a
  # materiality threshold (15% macro divergence + ≥3 redistributable meals) and is not reliably
  # reachable / assertable over the black-box HTTP surface in one synchronous scenario; the re-opt
  # tail stays deferred (its empty-state slice is covered green by planner.feature PLAN-22). Un-pend
  # the re-opt tail when an HTTP-drivable re-opt trigger + deterministic suggestion read lands.
  Scenario: The week plan is cooked and a slot is marked cooking from real scheduled recipes
    Given a fresh registered and logged-in user
    And the user has a household
    And the user has plannable recipes in their catalogue
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

  @xj05
  # XJ-05 full provider loop (now GREEN via the FakeGroceryProvider promotion: it ships under the
  # e2e profile as a @Component @Primary in the providers pocket, and the /test-support/grocery/
  # provider control plane drives the delivered + substitution mutators over HTTP). Chains the full
  # GROC-15 quote -> GROC-16 place -> GROC-17 confirm -> GROC-19 substitution review -> GROC-18
  # reconcile cascade with the planner spine (plan + shopping list) on the front and the planner-
  # facing inventory bundle on the back — the Grocery -> Provisions -> Planner relay end-to-end.
  Scenario: A provider grocery order is quoted, placed, confirmed, and reconciled to inventory
    Given a fresh registered and logged-in user
    And the user has a household
    And the user has plannable recipes in their catalogue
    And the AI will pick the recommended plan candidate
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they request the shopping list for that plan
    Then the shopping list lists the unmet ingredient lines for this user
    And the user has the fake grocery provider enabled
    When they draft a provider grocery order from that shopping list
    And they quote that provider grocery order
    And they place that provider grocery order
    And they mark that provider grocery order user-confirmed
    And the fake provider is armed to deliver with one substitution
    And they refresh the status of that provider grocery order
    Then the provider grocery order has one outstanding substitution proposal for this user
    When they accept the outstanding substitution proposal on that order
    Then the substitution is accepted and the provider grocery order is reconciled to inventory for this user
    And their planner bundle reflects the fulfilled grocery inventory for this user

  # ============================================================================
  # XJ-06 — Onboarding cold start: register -> seed models + household -> first plan
  # ============================================================================

  @xj06
  # XJ-06 buildable onboarding spine (AUTH-01/05 -> PREF-01 hard constraints -> NUT-01 targets ->
  # HH-01 household -> PLAN-06/07 -> PLAN-13): the "fresh user with a random handle" spine every
  # other journey folds into its Authenticated precondition. Register, set safety-critical hard
  # constraints, set nutrition targets, create the household, then generate (cold-start; the empty
  # pool still yields a plan) and accept it.
  # NOTE: the NUT-01 nutrition-targets leg is GREEN since nutrition-01c — PUT /nutrition/targets is
  # an UPSERT, so a fresh user's first PUT (expectedVersion 0) CREATES the row from the supplied
  # values instead of 404ing. Generate also reads nutrition via an .ifPresent fallback, so the
  # targets exist either way.
  Scenario: A new user onboards models and a household, then generates and accepts a first plan
    Given a fresh registered and logged-in user
    And the user has initialised hard constraints
    When they set their nutrition targets
    Then the targets are stored and returned for this user
    And the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household

  @xj06
  # XJ-06 cold-start catalogue bootstrap (now GREEN via the planner's Tier-2 cold-start gate). A
  # fresh user with an EMPTY catalogue generates: the catalogue is below the cold-start threshold
  # (3 × distinct-slot-kinds), so the gate fires and runs DiscoveryService.runJobSync, which in the
  # e2e profile imports recipes from the deterministic E2eSeedDiscoverySource (breakfast/lunch/dinner
  # seeds — NOT the real web/Google sources). The discovery runner gates every candidate through a
  # DISCOVERY_FILTERING AI call, so an accept-all canned response is primed first; without it the
  # unprimed stub would drop all candidates and the catalogue would stay empty. The re-read pool then
  # fills the plan's slots, the plan is flagged coldStart = true. We do NOT seed recipes here — the
  # empty catalogue is precisely what triggers the gate.
  Scenario: Cold start bootstraps the catalogue so the first plan has scheduled recipes
    Given a fresh registered and logged-in user
    And the user has initialised hard constraints
    Given the user has a household
    And the AI will pick the recommended plan candidate
    And the AI will return this DISCOVERY_FILTERING response:
      """
      { "relevant": true, "confidence": 0.95, "reason": "relevant e2e cold-start seed" }
      """
    When they generate a plan for a week
    Then the first plan is a cold-start plan with scheduled recipes for this household
