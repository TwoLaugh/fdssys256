@auth
Feature: Auth — register, log in, session behaviour, and the headline errors
  The auth gate is the precondition for nearly every other domain pathway
  (see e2e/pathways/auth.md). This wave-1 feature exercises the high-value
  happy paths (register, login, authenticated read, logout — GAP-80 ruled
  "ship logout in v1") plus the headline errors (duplicate register, wrong
  password, unknown username, unauthenticated access).

  Every scenario is self-contained (each registers its OWN user with a random
  handle) and self-scoped (assertions check THIS user's identity, never global
  counts) per decision D5 — so the feature runs in both clean and soak mode.

  # AUTH-05 + AUTH-09/AUTH-15: log in then read own account.
  Scenario: A registered user logs in and reads their own account
    Given a fresh anonymous visitor with a random username
    When they register with that username and a valid password
    Then registration succeeds and returns their new account identity
    When they log in with the same credentials
    Then login succeeds and a session is established
    When they request their own account while authenticated
    Then the account read succeeds and shows the same username and user id

  # AUTH-10 + AUTH-12: logout ends the session; protected reads are then denied.
  Scenario: Logging out ends the session and denies further protected reads
    Given a fresh anonymous visitor with a random username
    When they register with that username and a valid password
    Then registration succeeds and returns their new account identity
    When they request their own account while authenticated
    Then the account read succeeds and shows the same username and user id
    When they log out
    Then the logout succeeds
    When they request their own account while authenticated
    Then the request is rejected as unauthenticated

  # AUTH-04: a second registration with the same username is a conflict.
  Scenario: Registering a username that already exists is rejected as a conflict
    Given a fresh anonymous visitor with a random username
    When they register with that username and a valid password
    Then registration succeeds and returns their new account identity
    When a second visitor attempts to register with the same username
    Then the registration is rejected as a username conflict

  # AUTH-06: a valid username with the wrong password is rejected, no session issued.
  # "No session issued" is asserted on the login RESPONSE itself (401 + no AUTH_SESSION
  # Set-Cookie), made on a fresh anonymous client — NOT by a follow-up /me read. The register
  # step (auto-login on the SHARED client) only exists to make the username valid/existing, so a
  # subsequent shared-client /me would legitimately be 200 and must not be asserted as 401.
  Scenario: Logging in with the wrong password is rejected
    Given a fresh anonymous visitor with a random username
    When they register with that username and a valid password
    Then registration succeeds and returns their new account identity
    When they attempt to log in with the wrong password
    Then the login is rejected as invalid credentials

  # AUTH-07: an unknown username is rejected (same generic error as wrong password).
  Scenario: Logging in with an unknown username is rejected
    Given a fresh anonymous visitor with a random username
    When they attempt to log in with an unknown username
    Then the login is rejected as invalid credentials

  # AUTH-12: any protected action with no session is denied before domain logic runs.
  # Per the pathway, the action is "any protected (NON-auth) action" — so this hits a
  # user-scoped domain read (nutrition targets) anonymously, not an auth-domain endpoint,
  # asserting the deny-by-default gate fires before domain logic runs.
  Scenario: Accessing a protected action with no session is denied
    Given a fresh anonymous visitor with a random username
    When they request a protected domain resource with no session
    Then the request is rejected as unauthenticated
