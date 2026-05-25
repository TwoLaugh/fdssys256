@grocery
Feature: Grocery — manual fulfilment to inventory; shopping list / orders / price-history pending
  The Grocery domain is the bridge between the planner's output and a real-world
  shop, designed as four tiers — shopping list, manual fulfilment, provider order,
  price history (see e2e/pathways/grocery.md). This Batch-3 feature exercises the
  ONLY grocery surface that exists in the running app today and @pendings the rest
  with a precise reason each.

  The load-bearing finding that shapes this feature (from mapping the codebase):
  - THERE IS NO GROCERY MODULE. No shopping-list, order-lifecycle, substitution, or
    price-history HTTP surface exists in src/main (the grocery domain is "designed-
    but-not-yet-built" per the pathway doc, and the shopping-list ownership is a
    cross-doc HLD-GAP between grocery and the planner). The one buildable-green
    grocery behaviour is the manual-fulfilment LEG of the flagship (GROC-08 / the
    manual-only variant of GROC-36): a confirmed order is imported and lands in
    Provisions inventory via POST /api/v1/provisions/grocery-import (200), the same
    addToInventory write every fulfilment path makes. It is idempotent on
    (userId, supplier, orderRef) — a replay yields 409 (GROC-24-adjacent: an applied
    order cannot be re-applied).

  Each scenario registers its OWN fresh user (D5 self-contained data); the import is
  per-user and idempotent on a per-run-random orderRef, so each scenario is
  self-contained and self-scoped — never a global count.

  # ----- Manual fulfilment -> inventory (green: the only built grocery surface) -----

  @smoke
  # GROC-08 / GROC-36 (manual-only variant): a confirmed grocery order is fulfilled
  # into Provisions inventory and refreshes the supplier-price cache (the price-history
  # learning loop's `manual`/`paid` feed).
  Scenario: A confirmed grocery order is fulfilled into the user's inventory
    Given a fresh registered and logged-in user
    When they fulfil a confirmed grocery order
    Then the order lands in their inventory and price cache for this user

  # GROC-24-adjacent (idempotency): replaying the same (supplier, orderRef) import is a
  # 409 — an already-applied order cannot be re-applied.
  Scenario: Replaying an already-fulfilled grocery order is rejected as a conflict
    Given a fresh registered and logged-in user
    And the user has fulfilled a confirmed grocery order
    When they fulfil that same grocery order again
    Then the grocery fulfilment is rejected as a conflict

  # GROC-08 (validation): a grocery order with no lines is a 400 (@NotEmpty lines).
  Scenario: Fulfilling a grocery order with no lines is rejected as a validation error
    Given a fresh registered and logged-in user
    When they fulfil a grocery order with no lines
    Then the grocery fulfilment is rejected as a validation error

  # GROC cross-cutting: fulfilling an order with no session is denied.
  Scenario: Fulfilling a grocery order with no session is denied
    Given a fresh registered and logged-in user
    When an anonymous client fulfils a grocery order with no session
    Then the request is rejected as unauthenticated

  # ----- @pending: tiers with no HTTP surface in the running app -----

  @pending
  # GROC-01 (shopping list). PENDING: no shopping-list HTTP surface exists in src/main —
  # there is no grocery module, and the list calculation's ownership is a cross-doc
  # HLD-GAP (grocery says it exposes the list; provision-model says the Planner owns the
  # calculation). Neither module exposes a "GET shopping list" endpoint. Un-pend when a
  # shopping-list read surface lands.
  Scenario: A user views the shopping list for the active plan
    Given a fresh registered and logged-in user
    When they request the shopping list
    Then the shopping list lists the unmet ingredient lines for this user

  @pending
  # GROC-03 (cost projection). PENDING: depends on the shopping-list surface above plus a
  # Tier-4 price-history read, neither of which exists in the running app.
  Scenario: A user views the cost projection with a confidence and stale-data summary
    Given a fresh registered and logged-in user
    When they request the shopping list cost projection
    Then the cost projection carries an estimate and confidence for this user

  @pending
  # GROC-15/16/17/18 (provider order lifecycle: quote -> place -> confirm -> deliver ->
  # reconcile). PENDING: Tier-3 provider automation (Tesco via the AI navigator) is
  # designed-but-unbuilt — there is no GroceryProvider, no order entity, and no order
  # HTTP surface. Un-pend when the provider order module lands.
  Scenario: A user places, confirms, and reconciles a provider order
    Given a fresh registered and logged-in user
    When they place a provider grocery order
    Then the provider order is awaiting user confirmation for this user

  @pending
  # GROC-19 (substitution resolution at delivery). PENDING: substitutions live on the
  # unbuilt provider-order lifecycle (no SubstitutionProposal entity / endpoint exists).
  Scenario: A user resolves a substitution proposal at delivery
    Given a fresh registered and logged-in user
    When they resolve a delivery substitution proposal
    Then the substitution is applied to inventory for this user

  @pending
  # GROC-30 (learned price view). PENDING: the Tier-4 price-history aggregate has no
  # direct user-facing read endpoint (GG12: whether a per-ingredient learned price is
  # even surfaced directly is itself an HLD-GAP); it only feeds the planner cost sub-score
  # internally. Un-pend if/when a learned-price read surface lands.
  Scenario: A user views the learned price for an ingredient
    Given a fresh registered and logged-in user
    When they request the learned price for an ingredient
    Then the learned price carries an estimate and confidence range for this user
