@grocery
Feature: Grocery — manual fulfilment to inventory; shopping list / price-history; provider lifecycle + substitution un-pended (Batch 2)
  The Grocery domain is the bridge between the planner's output and a real-world
  shop, designed as four tiers — shopping list, manual fulfilment, provider order,
  price history (see e2e/pathways/grocery.md). With 01b/01c + the e2e fake-provider
  promotion merged, every tier is exercised here:
    - Tier 1: shopping-list calculation + cost projection (GROC-01 / GROC-03).
    - Tier 2: manual fulfilment → Provisions inventory (GROC-08 / GROC-36 manual-only).
    - Tier 3: provider order lifecycle (GROC-15 quote → GROC-16 place → GROC-17 confirm →
      GROC-18 deliver/reconcile, plus cancel) + GROC-19 substitution resolution.
    - Tier 4: learned-price aggregate (GROC-30).

  The load-bearing finding that shapes this feature (from reading the controllers
  + service implementation):
  - SHOPPING LIST. Recalculation is owned by the Grocery module
    (POST /api/v1/grocery/shopping-lists/recalculate); a same-(planId, generation)
    re-run is idempotent. After a plan is generated, a ShoppingListRecalcListener
    AFTER_COMMIT also writes the list, but because the listener runs in
    REQUIRES_NEW after the publisher commits the e2e scenarios trigger the
    recalculate explicitly (deterministic, no timing race) and then read back via
    GET /api/v1/grocery/shopping-lists/current?planId=... .
  - COST PROJECTION. The Tier-1 cost projection is NOT a separate endpoint — its
    fields (estimatedTotalPence, costConfidence, staleIngredientCount) live
    directly on ShoppingListDto, computed in Step 6 of the calculator from the
    Tier-4 batch aggregate read. For a fresh user with no observations whose
    seeded recipes use a mapping key the reference seed does not cover, the
    cost fields settle to null and every line is counted as stale — the
    scenario asserts the SHAPE the read carries, not a specific cost.
  - LEARNED PRICE. The Tier-4 user-facing read is the cross-store aggregate
    (GET /api/v1/grocery/price-history/aggregates?ingredientKey=...): an empty
    history returns 404 (no estimate); a single manual observation
    (POST /api/v1/grocery/price-history/observations/manual) seeds the household
    so the aggregate returns 200 + (pointEstimatePence, confidence, range).
    The single-user-mode writer uses userId AS the household scope, so the
    seeded observation and the aggregate read line up for a fresh user.
  - MANUAL FULFILMENT. The one buildable-green grocery behaviour that pre-dated
    01b/01c is the manual-fulfilment LEG of the flagship (GROC-08 / the
    manual-only variant of GROC-36): a confirmed order is imported and lands in
    Provisions inventory via POST /api/v1/provisions/grocery-import (200), the
    same addToInventory write every fulfilment path makes. It is idempotent on
    (userId, supplier, orderRef) — a replay yields 409 (GROC-24-adjacent: an
    applied order cannot be re-applied).

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

  # ----- Shopping list (01b) + cost projection (01b reading 01c aggregates) -----

  # GROC-01 (shopping list). The user assembles the planner's hard precondition (household +
  # plannable catalogue), generates a plan, recalculates the shopping list deterministically
  # (idempotent), and reads it back via the current-by-plan endpoint. The read returns the
  # ShoppingListDto with one line per UNFILLED ingredient demand for this user's plan.
  Scenario: A user views the shopping list for the active plan
    Given a fresh registered and logged-in user
    And the user has a household
    And the user has plannable recipes in their catalogue
    And the AI will pick the recommended plan candidate
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they request the shopping list for that plan
    Then the shopping list lists the unmet ingredient lines for this user

  # GROC-03 (cost projection). The cost projection lives directly on the ShoppingListDto:
  # estimatedTotalPence + costConfidence + staleIngredientCount, computed in Step 6 of the
  # calculator from the Tier-4 batch aggregate read. The plannable-recipe seed's `chicken breast`
  # line (canonical space-form) matches the reference-price snapshot, so the calculator resolves a
  # reference aggregate: the projection carries a REAL non-null total + confidence. The reference
  # fallback is flagged stale, so staleIngredientCount is positive — the signal the estimate came
  # from the reference snapshot, not a paid/observed price.
  Scenario: A user views the cost projection with a confidence and stale-data summary
    Given a fresh registered and logged-in user
    And the user has a household
    And the user has plannable recipes in their catalogue
    And the AI will pick the recommended plan candidate
    When they generate a plan for a week
    Then a generated plan is created for this household
    When they request the shopping list for that plan
    Then the shopping list carries a resolved cost projection for this user

  # ----- Tier 3 — provider order lifecycle (GROC-15..18) + substitution (GROC-19) -----
  # The fake provider (deterministic; promoted onto the e2e runtime classpath as
  # @Component @Profile("e2e") @Primary — boundary-compliant in the providers pocket)
  # is keyed "fake"; its mutators (failure-mode, delivered, reset) are driven over the
  # e2e-only HTTP control plane at /test-support/grocery/provider/... .

  # GROC-15 (quote leg). The user enables the provider, assembles a shopping list from a
  # real plan, drafts an order from the list, and runs a quote pass — the order reaches
  # QUOTED and the provider's quote prices DERIVE from the reference snapshot.
  Scenario: A user quotes a provider grocery order from their list
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
    Then the provider grocery order is in draft for this user
    When they quote that provider grocery order
    Then the provider grocery order is quoted with a quoted total for this user

  # GROC-16 (place leg). The quoted order is placed: the fake drives the basket and STOPS
  # at checkout, the order auto-advances to AWAITING_USER_CONFIRMATION, and the confirm
  # link is surfaced for the user to act on in the provider UI.
  Scenario: A user places a quoted provider grocery order
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
    Then the provider grocery order is awaiting user confirmation with a confirm link for this user

  # GROC-17 (confirm leg). The user marks the order user-confirmed (the in-app surface that
  # mirrors them clicking "confirm" in the provider's UI). Status moves to CONFIRMED.
  Scenario: A user confirms a placed provider grocery order
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
    Then the provider grocery order is confirmed for this user

  # GROC-18 (deliver / reconcile leg, no-substitution variant). With the fake's delivered
  # flag armed (no substitutions), a refresh-status call surfaces DELIVERED and the inline
  # reconcile runs — the order lands at RECONCILED, no outstanding proposals, items in
  # Provisions inventory.
  Scenario: A confirmed provider grocery order is delivered and reconciled to inventory
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
    And the fake provider is armed to deliver with no substitutions
    And they refresh the status of that provider grocery order
    Then the provider grocery order is reconciled to inventory for this user

  # GROC-19 (substitution resolution at delivery). With the fake armed to deliver WITH a
  # substitution, a refresh-status persists a PENDING_USER_REVIEW proposal; the order is
  # DELIVERED, blocked from reconciling until resolved. Accepting the proposal clears the
  # gate, reconciliation runs, and the order lands at RECONCILED.
  Scenario: A user resolves a delivery substitution proposal and the order reconciles
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

  # GROC-30 (learned price view). 01c shipped the Tier-4 aggregate read at
  # GET /api/v1/grocery/price-history/aggregates?ingredientKey=...&store=... — an empty
  # history is a 404; a single manual observation (POST .../observations/manual) seeds the
  # household, and the aggregate then returns 200 with (pointEstimatePence, confidence,
  # range, sample count). In single-user mode the writer uses userId as the household scope,
  # so a fresh user's seeded observation lines up with the aggregate read.
  Scenario: A user views the learned price for an ingredient
    Given a fresh registered and logged-in user
    And they have recorded a manual price observation for an ingredient
    When they request the learned price for that ingredient
    Then the learned price carries an estimate and confidence range for this user
