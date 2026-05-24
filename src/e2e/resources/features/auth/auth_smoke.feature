@smoke @auth
Feature: Auth smoke — register, log in, authenticated read
  The auth gate is the precondition for nearly every other domain pathway
  (see e2e/pathways/auth.md). This smoke slice proves the whole toolchain
  end-to-end against the running prod-parity stack: a fresh user can register,
  log in, and make one authenticated read.

  Scenarios are self-contained (each registers its OWN user with a random
  handle) and self-scoped (assertions check THIS user's identity, never global
  counts) per decision D5 — so the same feature runs in both clean and soak mode.

  # AUTH-01 (register) + AUTH-05 (login) + AUTH-09/AUTH-15 (authenticated read of own identity).
  Scenario: A fresh user registers, logs in, and reads their own account
    Given a fresh anonymous visitor with a random username
    When they register with that username and a valid password
    Then registration succeeds and returns their new account identity
    When they log in with the same credentials
    Then login succeeds and a session is established
    When they request their own account while authenticated
    Then the account read succeeds and shows the same username and user id
