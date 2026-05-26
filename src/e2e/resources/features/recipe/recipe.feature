@recipe
Feature: Recipe — manual create, read, edit→new version, rating, and key errors
  The Recipe domain is the system's food-as-food catalogue (see
  e2e/pathways/recipe.md). This wave-1 feature exercises the buildable
  high-value paths: manual create (RCP-01), read by id (RCP-11), a manual
  edit that creates a new version (RCP-19/RCP-20), and a quick taste rating
  (RCP-58), plus the headline errors (missing required fields RCP-02,
  not-found read RCP-12, unreachable URL import RCP-04).

  Each scenario registers its OWN fresh user (D5 self-contained data) and
  asserts only on THIS user's recipe id (self-scoped) — never global counts.

  @smoke
  # RCP-01: manual create lands in the user catalogue, user_verified, ACTIVE at v1.
  Scenario: A user manually creates a recipe and reads it back
    Given a fresh registered and logged-in user
    When they create a manual recipe
    Then the recipe is created in their user catalogue at version 1
    When they read that recipe by id
    Then the recipe read returns the same recipe with its current version body

  # RCP-19 + RCP-20: a manual edit always creates a new version on the current branch.
  Scenario: A manual edit creates a new version with the manual-edit trigger
    Given a fresh registered and logged-in user
    When they create a manual recipe
    Then the recipe is created in their user catalogue at version 1
    When they manually edit that recipe
    Then a new version 2 is created with the manual-edit trigger and a change reason

  # RCP-58: a one-tap taste rating is recorded against the current version.
  Scenario: A user records a quick taste rating on a recipe version
    Given a fresh registered and logged-in user
    When they create a manual recipe
    Then the recipe is created in their user catalogue at version 1
    When they give a quick taste rating on the current version
    Then the rating is recorded against that version

  # RCP-02: a manual create missing a required field is rejected; nothing stored.
  Scenario: Manually creating a recipe with no name is rejected
    Given a fresh registered and logged-in user
    When they submit a manual recipe with a blank name
    Then the recipe creation is rejected as a validation error

  # RCP-02 variation: a manual create with an empty ingredient list is rejected.
  Scenario: Manually creating a recipe with no ingredients is rejected
    Given a fresh registered and logged-in user
    When they submit a manual recipe with no ingredients
    Then the recipe creation is rejected as a validation error

  # RCP-12: reading a recipe id that never existed is a not-found error.
  Scenario: Reading a recipe that does not exist returns not found
    Given a fresh registered and logged-in user
    When they read a recipe by a random non-existent id
    Then the recipe read is rejected as not found

  # RCP-04: a URL import that cannot be fetched fails fast; no partial recipe stored.
  Scenario: Importing from an unreachable URL fails and stores nothing
    Given a fresh registered and logged-in user
    When they import a recipe from an unreachable URL
    Then the import is rejected as an import failure

  # RCP-03 — Happy URL import. The import fetches the app's OWN hermetic fixture page
  # (E2eRecipeFixtureController, e2e-profile only) over a REAL loopback HTTP GET and runs the
  # REAL deterministic JSON-LD extraction (NOT the AI double), so the fetch + parse + create
  # path is exercised end-to-end with no external dependency.
  Scenario: A user imports a recipe from a reachable recipe URL
    Given a fresh registered and logged-in user
    When they import a recipe from a reachable recipe URL
    Then the recipe is imported into their user catalogue with imported data quality
