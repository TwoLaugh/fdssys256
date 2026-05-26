@cross @xj01
Feature: XJ-01 — web recipe import to internal format, USDA nutrition, edit, recalculation, logged
  The flagship Recipe<->Nutrition integration journey (see
  e2e/pathways/cross-journeys.md XJ-01). A user imports a recipe from the web,
  the system converts it to the internal format and derives nutrition from
  USDA, the user manually edits an ingredient which creates a new version and
  triggers a recalculation, and the change is recorded in the version log.

  Step trace: AUTH-05 -> RCP-03 -> NUT-26 -> RCP-34 -> RCP-19 -> RCP-20 -> NUT-31 -> RCP-15.
  Self-scoped to this user's single imported recipe id.

  # Step 1 (RCP-03) imports the app's OWN hermetic fixture page (E2eRecipeFixtureController,
  # e2e-profile only) via a REAL loopback HTTP GET and the REAL deterministic JSON-LD extraction
  # (the URL fetch is real, not the AI double). The fixture's realistic whole-food ingredients
  # feed the USDA nutrition derive (NUT-26); the edit then drives the recalc relay and version log.
  Scenario: Import a web recipe, derive USDA nutrition, edit an ingredient, and recalculate
    Given a fresh registered and logged-in user
    When they import a recipe from a reachable recipe URL
    Then the recipe is imported into their user catalogue with imported data quality
    And the imported recipe has internally derived nutrition status
    When they manually edit that recipe
    Then a new version 2 is created with the manual-edit trigger and a change reason
    And the recipe's current version body reflects the edit and a recalculated nutrition status
