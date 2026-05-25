@provisions
Feature: Provisions — inventory lifecycle, waste log + summary, supplier cache, grocery import
  The Provisions domain is the physical-world state model (see
  e2e/pathways/provisions.md): inventory, waste, equipment, budget, and the
  supplier-product cache. This Batch-2 feature exercises the high-value paths
  that are buildable GREEN over HTTP from a fresh user — provisions has real
  POST create paths, so no seeder is needed. It covers the inventory
  create→read→update→soft-delete lifecycle (PROV-01/06/07/12/15), waste log +
  history + summary (PROV-19/22), supplier-products upsert + search
  (PROV-33/29), the grocery-order import leg (PROV-03), the planner-bundle
  empty read, and the headline validation/not-found/401 errors (PROV-02/07).

  Each scenario registers its OWN fresh user (D5 self-contained data) and asserts
  only on THIS user's provisions state (self-scoped) — never global counts — so the
  feature runs in both clean and soak mode. (Inventory and waste are per-user; the
  supplier cache is global reference data, so those scenarios key on a per-run-random
  natural key and assert the row is found by its own key, never a table size.)

  # ----- Inventory lifecycle -----

  @smoke
  # PROV-01 + PROV-06 + PROV-07 + PROV-12 + PROV-15: the full inventory lifecycle over real HTTP —
  # manually add a fridge item, read it back, correct its quantity (PUT with expectedVersion), and
  # soft-delete it (drops out of the active list).
  Scenario: A user adds, reads, corrects, and soft-deletes an inventory item
    Given a fresh registered and logged-in user
    When they add a fridge inventory item
    Then the item is stored and visible in their inventory for this user
    When they read that inventory item by id
    Then the inventory item read returns the same item for this user
    When they correct that inventory item's quantity
    Then the corrected quantity is reflected on a read for this user
    When they soft-delete that inventory item
    Then the item is no longer in their active inventory for this user

  @smoke
  # PROV-06 (cold-start): a fresh user's active inventory is an empty page, not an error.
  Scenario: Inventory is empty for a fresh user
    Given a fresh registered and logged-in user
    When they list their inventory
    Then the inventory is empty for this user

  # PROV-07: reading an unknown inventory item id is a clean not-found.
  Scenario: Reading a non-existent inventory item returns not found
    Given a fresh registered and logged-in user
    When they read an inventory item by a random non-existent id
    Then the inventory item read is rejected as not found

  # PROV-02 (validation): a manual add with a blank name is rejected (name is @NotBlank).
  Scenario: Adding an inventory item with no name is rejected as a validation error
    Given a fresh registered and logged-in user
    When they submit a manual inventory item with no name
    Then the inventory item create is rejected as a validation error

  # PROV-02 (structural validation): a FRIDGE item with STATUS tracking is an invalid combination
  # (@ValidStorageLocation requires FRIDGE -> QUANTITY) — rejected before any write.
  Scenario: Adding a fridge item with status tracking is rejected as a validation error
    Given a fresh registered and logged-in user
    When they submit a fridge inventory item with status tracking
    Then the inventory item create is rejected as a validation error

  # PROV cross-cutting: a protected inventory read with no session is denied before domain logic.
  Scenario: Listing inventory with no session is denied
    Given a fresh registered and logged-in user
    When an anonymous client lists inventory with no session
    Then the request is rejected as unauthenticated

  # ----- Waste -----

  @smoke
  # PROV-19 + PROV-22: log a waste entry, see it in history, and see it reflected in the per-user
  # summary aggregate (totalEntries=1, EXPIRED=1 for this user).
  Scenario: A user logs waste and sees it in history and the summary
    Given a fresh registered and logged-in user
    When they log a waste entry
    Then the waste entry is recorded for this user
    And the waste entry appears in their waste history for this user
    When they read their waste summary for the last 90 days
    Then the waste summary reflects the logged entry for this user

  # PROV-22 (empty slice): a fresh user's waste history is an empty page.
  Scenario: Waste history is empty for a fresh user
    Given a fresh registered and logged-in user
    When they read their empty waste history
    Then the waste history is empty for this user

  # PROV (error): the waste summary rejects an inverted date range (from > to) with 400.
  Scenario: A waste summary with an inverted date range is rejected as a validation error
    Given a fresh registered and logged-in user
    When they read their waste summary with an inverted date range
    Then the waste summary is rejected as a validation error

  # ----- Supplier products -----

  @smoke
  # PROV-33 + PROV-29: cache a supplier product (insert -> 201) and find it by its mapping key.
  # The cache is global reference data, so the row is keyed on a per-run-random (supplier, productId)
  # and found by its own per-run mapping key — never asserting the global cache size.
  Scenario: A user caches a supplier product and finds it by mapping key
    Given a fresh registered and logged-in user
    When they cache a supplier product
    Then the supplier product is created
    When they search supplier products by that mapping key
    Then the cached supplier product is found by its mapping key

  # ----- Grocery import (PROV-03 leg) -----

  # PROV-03: the grocery module's order -> inventory + supplier-cache write, exercised over the real
  # POST. A fresh user with no prior row gets one added inventory item + a refreshed supplier product.
  Scenario: A confirmed grocery order is applied to inventory and the supplier cache
    Given a fresh registered and logged-in user
    When they import a confirmed grocery order
    Then the order is applied to inventory and the supplier cache for this user

  # ----- Planner bundle (cross-module read) -----

  # PROV cross-module: the planner-facing read snapshot is the empty shape for a fresh user
  # (empty collections, null budget) — not an error.
  Scenario: The planner bundle is empty for a fresh user
    Given a fresh registered and logged-in user
    When they read their planner bundle
    Then the planner bundle is empty for this user
