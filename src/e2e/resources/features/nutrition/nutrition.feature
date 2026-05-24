@nutrition
Feature: Nutrition — targets CRUD, intake logging, USDA-derived nutrition, key errors
  The Nutrition domain holds what the body needs (targets) and what it gets
  (intake), plus the Nutrition Engine that maps ingredients to USDA/OFF (see
  e2e/pathways/nutrition.md). This wave-1 feature exercises the buildable
  paths: set + read targets (NUT-01), log a standalone food item (NUT-19),
  USDA-derived ingredient nutrition (NUT-26), plus an invalid-target error
  (NUT-05).

  Each scenario registers its OWN fresh user (D5) and asserts only on THIS
  user's nutrition state (self-scoped) — never global aggregates.

  @pending
  # NUT-01: accept/set targets, model CONFIGURED, then read them back.
  # PENDING: PUT /api/v1/nutrition/targets is update-only — it 404s for a user with no targets row
  # (see TargetsController + NutritionServiceImpl.updateTargets, and the passing
  # TargetsFlowIT.put_returns404_whenNotInitialised). A fresh user's row is created ONLY by the
  # auto-seed `initialiseTargets` flow, which is DEFERRED to nutrition-01c (no public create/POST
  # endpoint exists in 01a — the ITs seed the row directly via the repository). The harness is a
  # black-box HTTP client (D2) and cannot seed over HTTP, so this set+read flow is not buildable
  # green yet. The full glue is written; drop @pending once the initialise endpoint lands in 01c.
  Scenario: A user sets their nutrition targets and reads them back
    Given a fresh registered and logged-in user
    When they set their nutrition targets
    Then the targets are stored and returned for this user
    When they read their nutrition targets
    Then the targets read returns the stored targets for this user

  # NUT-19: a standalone food item is logged with its source; the day row is auto-created.
  Scenario: A user logs a standalone food item for today
    Given a fresh registered and logged-in user
    When they log a standalone snack for today
    Then the snack is recorded on today's intake for this user

  # NUT-26: ingredient nutrition is derived by mapping to USDA/OFF (external deps real in CI).
  Scenario: Looking up a common ingredient derives nutrition from USDA
    Given a fresh registered and logged-in user
    When they look up nutrition for the ingredient "banana"
    Then the lookup returns a mapped nutrition record for that ingredient

  # NUT-05: a target value outside its bounds is rejected; existing targets unchanged.
  Scenario: Setting a negative macro target is rejected
    Given a fresh registered and logged-in user
    When they submit nutrition targets with a negative protein target
    Then the targets update is rejected as a validation error
