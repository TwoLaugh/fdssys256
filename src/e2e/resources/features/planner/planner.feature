@planner
Feature: Planner — plan generation, accept/reject/abandon/revert lifecycle, empty reads, and errors
  The Planner is the week-scale plan orchestrator (see e2e/pathways/planner.md).
  This Batch-2 feature exercises every path that is buildable GREEN over HTTP
  from a fresh user, and @pendings the two paths that need a recipe pool we
  cannot assemble over HTTP (with a precise one-line reason each).

  The load-bearing findings that shape this feature (from reading PlansController,
  PlanComposer, PlanCompositionContextBuilder, PlanPersister, NoOpRecipePoolSource):
  - Generate's one hard precondition is a HOUSEHOLD. POST /api/v1/plans/generate
    403s a non-member; the context builder fans out per household member. There IS
    a real public create path — POST /api/v1/households makes the caller the primary
    member AND seeds default slot settings — so the precondition is assemblable over
    HTTP with NO seeder. Preferences/nutrition/provisions are read with .ifPresent /
    an empty-bundle fallback, so their absence does not block generation.
  - Generate is SYNCHRONOUS: compose() is @Transactional and the controller returns
    201 + the persisted PlanDto inline (200 on an Idempotency-Key replay). No polling.
  - The recipe pool is ALWAYS empty in the running app (NoOpRecipePoolSource; the
    only @Primary overrides live under src/test, not the e2e stack). So Stage A
    produces no candidates, Stage C (the AI pick) is SKIPPED, and the composer
    persists a minimal GENERATED + qualityWarning plan with NO days/slots rather
    than failing. This makes the generate→accept→ACTIVE lifecycle green, but the
    plan carries no scheduled recipes and no slots.

  Each scenario mints its OWN fresh user, creates its OWN household, and plans a
  per-scenario-random week, asserting only on THIS plan's id/status/generation
  (self-scoped) — never a global plan count — so it runs in clean and soak mode.

  # ----- Generate + lifecycle (green) -----

  @smoke
  # PLAN-01 + PLAN-13: generate a plan (201 GENERATED, generation 1) then accept it (-> ACTIVE).
  # The plan is qualityWarning with no slots (empty recipe pool) but the lifecycle is the real path.
  Scenario: A user generates a plan and accepts it
    Given a fresh registered and logged-in user
    And the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household

  # PLAN-14: a generated plan can be rejected (-> REJECTED).
  Scenario: A user rejects a generated plan
    Given a fresh registered and logged-in user
    And the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they reject that plan
    Then the plan is rejected for this household

  # PLAN-15: an active plan can be abandoned mid-week (-> ABANDONED).
  Scenario: A user abandons an active plan
    Given a fresh registered and logged-in user
    And the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household
    When they abandon that plan
    Then the plan is abandoned for this household

  # PLAN-30: revert-to-historical (the #196 contract). POST /api/v1/plans/revert takes a body
  # {"targetHistoricalPlanId": <uuid>} and copies the chosen historical plan's content onto a NEW
  # active generation (gen 2, GENERATED), re-checking the caller's current hard constraints and
  # refilling stripped slots; the prior active is superseded (the new plan's replacesPlanId points
  # back at it). "In the caller's household history" means any plan in a household the caller is a
  # member of — so reverting to the household's own currently-active plan is the simplest valid
  # target (RevertToPlanCoordinator.hydrateTarget gates on canAccessHousehold). 201 Created.
  Scenario: A user reverts to a historical plan in their household
    Given a fresh registered and logged-in user
    And the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household
    When they revert to that plan as a historical target
    Then a fresh generation is created from the historical plan and the prior is superseded for this household

  # ----- Illegal transitions (green) -----

  # PLAN-17: accepting a plan that is already ACTIVE is an illegal transition (409).
  Scenario: Accepting an already-active plan is rejected as an illegal transition
    Given a fresh registered and logged-in user
    And the user has a household
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household
    When they accept that plan
    Then the accept is rejected as an illegal plan transition

  # PLAN-30 (error slice, headline guard): reverting to a plan that belongs to a DIFFERENT
  # household is "not in the caller's household history" -> 422 (RevertTargetNotInHistoryException).
  # This is the authoritative ownership check for revert (the target is in the body, not the path,
  # so there is no controller-level pre-auth). Constructed over HTTP by minting a SECOND user with
  # their own household + plan and having the first user target it.
  Scenario: Reverting to a plan in another household is rejected as not in history
    Given a fresh registered and logged-in user
    And the user has a household
    And another household has its own plan
    When they revert to the other household's plan as a historical target
    Then the revert is rejected because the target is not in the caller's household history

  # PLAN-30 (error slice): reverting to a target plan id that does not exist at all is a clean
  # not-found (404, PlanNotFoundException) — distinct from the 422 "exists but not yours" guard.
  Scenario: Reverting to a non-existent target plan returns not found
    Given a fresh registered and logged-in user
    And the user has a household
    When they revert to a random non-existent target plan
    Then the revert target read is rejected as not found

  # ----- Empty-state reads + not-found (green) -----

  @smoke
  # PLAN-29: the active-plan query for a never-planned week is a clean not-found.
  Scenario: Reading the active plan for a never-planned week returns not found
    Given a fresh registered and logged-in user
    And the user has a household
    When they read the active plan for a never-planned week
    Then the active plan read is rejected as not found

  # PLAN-28: the plan-history list for a never-planned week is empty (200, not an error).
  Scenario: Plan history is empty for a never-planned week
    Given a fresh registered and logged-in user
    And the user has a household
    When they read the plan history for a never-planned week
    Then the plan history is empty for this household

  # PLAN-22 (empty slice): a household with no material change has no pending re-opt suggestions.
  Scenario: Re-opt suggestions are empty for a fresh household
    Given a fresh registered and logged-in user
    And the user has a household
    When they read pending re-opt suggestions for this household
    Then the re-opt suggestion list is empty for this household

  # PLAN-29: reading an unknown plan id is a clean not-found.
  Scenario: Reading a non-existent plan returns not found
    Given a fresh registered and logged-in user
    When they read a plan by a random non-existent id
    Then the plan read is rejected as not found

  # PLAN cross-cutting: generating a plan with no session is denied before any planner logic.
  Scenario: Generating a plan with no session is denied
    Given a fresh registered and logged-in user
    When an anonymous client generates a plan with no session
    Then the request is rejected as unauthenticated

  # ----- Recipe-pool driven (green via the real catalogue pool — Tier-1 CatalogueRecipePoolSource) -----
  # The planner now plans from the recipe catalogue (caller's USER recipes ∪ SYSTEM). Seeding ≥3
  # plannable recipes per default-household slot kind over the real POST /api/v1/recipes create path
  # gives Stage A candidates, so a generated plan carries real scheduled-recipe slots — un-pending
  # the two paths that were waiting on a recipe-pool surface.

  # PLAN-18 (slot-state lifecycle): a generated plan with real slots, then PATCH its first slot
  # PLANNED -> COOKING and assert the transition. The seeded catalogue (≥3 recipes per slot kind)
  # gives Stage A candidates so the plan has slots to transition.
  Scenario: A user marks a planned slot as cooking
    Given a fresh registered and logged-in user
    And the user has a household
    And the user has plannable recipes in their catalogue
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they accept that plan
    Then the plan becomes active for this household
    When they mark the first slot of that plan cooking
    Then the slot is marked cooking for this household

  # PLAN-40 flagship (generate -> AI Stage-C pick -> scheduled recipes): the seeded catalogue makes
  # Stage A produce candidates so Stage C (the AI pick, TaskType PLANNER_STAGE_C) runs and the plan
  # carries scheduled recipes in its slots.
  Scenario: The AI picks a candidate and the plan carries scheduled recipes
    Given a fresh registered and logged-in user
    And the user has a household
    And the user has plannable recipes in their catalogue
    And the AI will pick the recommended plan candidate
    When they generate a plan for a week
    Then the generated plan has scheduled recipes in its slots for this household
