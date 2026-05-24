@cross @xj01
Feature: XJ-01 — web recipe import to internal format, USDA nutrition, edit, recalculation, logged
  The flagship Recipe<->Nutrition integration journey (see
  e2e/pathways/cross-journeys.md XJ-01). A user imports a recipe from the web,
  the system converts it to the internal format and derives nutrition from
  USDA, the user manually edits an ingredient which creates a new version and
  triggers a recalculation, and the change is recorded in the version log.

  Step trace: AUTH-05 -> RCP-03 -> NUT-26 -> RCP-34 -> RCP-19 -> RCP-20 -> NUT-31 -> RCP-15.
  Self-scoped to this user's single imported recipe id.

  @pending
  # PENDING: step 1 (RCP-03) imports a LIVE web page via deterministic JSON-LD/microdata
  # extraction (the URL fetch is real, not the AI double), so a stable whitelisted recipe URL
  # must be provisioned in CI before this can run green. The full step trace + glue is written
  # so later waves only need to swap in a stable URL fixture and drop the @pending tag.
  Scenario: Import a web recipe, derive USDA nutrition, edit an ingredient, and recalculate
    Given a fresh registered and logged-in user
    When they import a recipe from a reachable recipe URL
    Then the recipe is imported into their user catalogue with imported data quality
    And the imported recipe has internally derived nutrition status
    When they manually edit that recipe
    Then a new version 2 is created with the manual-edit trigger and a change reason
    And the recipe's current version body reflects the edit and a recalculated nutrition status
