@household
Feature: Household — create, roster, settings + audit, slot config, membership, and errors
  The Household domain is the multi-user composition layer (see
  e2e/pathways/household.md): membership + roles, shared-vs-individual slot
  settings, and the shared Provisions link. This Batch-3 feature exercises the
  rich, real HTTP surface that is buildable GREEN from a fresh user — household
  has real POST/PUT/DELETE paths AND seeds a default settings row on create, so
  create → read → settings-edit → member-admin are all buildable with NO seeder.

  The load-bearing findings that shape this feature (from reading HouseholdsController,
  HouseholdMembersController, HouseholdSettingsController, HouseholdInvitesController,
  and HouseholdServiceImpl):
  - CREATE is real and self-seeding. POST /api/v1/households makes the caller the
    sole PRIMARY member AND seeds a default HouseholdSettings row (breakfast/lunch/
    dinner/snack all shared, headcount 1, timeBudget 30). So GET /current,
    GET /{id}/settings, GET /{id}/slot-configuration all return 200, and the
    settings audit-log is an empty Page — no seeder needed.
  - Single household per user. A second create by the same user is 409
    (UserAlreadyInHousehold). PUT /{id}/settings is PRIMARY-only with optimistic
    expectedVersion (stale → 409); a no-op replace does not bump @Version.
  - A SECOND member is assemblable in one scenario. POST /current/members takes a
    TARGET userId in the body; we register a second account on a SEPARATE ApiClient
    (fresh cookie jar) purely to mint its userId, then the primary adds it — no
    second interactive login disturbs the primary's session.
  - Role/transition errors are real: demoting the last primary while others remain
    is 409 (LastPrimaryRemoval); a non-member's settings read/write 404/403s.

  Each scenario registers its OWN fresh primary user (D5 self-contained data) and
  asserts only on THIS household's roster/settings (self-scoped) — never global
  counts — so the feature runs in both clean and soak mode.

  # ----- Create + read (green) -----

  @smoke
  # HH-01 + HH-10: create a household (caller becomes sole primary) and read the roster.
  Scenario: A user creates a household and is its sole primary member
    Given a fresh registered and logged-in user
    When they create a household
    Then the household is created with this user as its primary member
    When they read their current household
    Then the household roster lists only this user as primary

  # HH-01 (no-household read): a user with no household gets a clean not-found.
  Scenario: Reading the current household before creating one returns not found
    Given a fresh registered and logged-in user
    When they read their current household
    Then the current-household read is rejected as not found

  # HH-01 (single-household invariant): a second create by the same user is a 409.
  Scenario: Creating a second household for the same user is rejected
    Given a fresh registered and logged-in user
    And the user has created a household
    When they create another household
    Then the second household creation is rejected as a conflict

  # HH-01 (validation): a blank household name is a 400.
  Scenario: Creating a household with a blank name is rejected as a validation error
    Given a fresh registered and logged-in user
    When they create a household with a blank name
    Then the household creation is rejected as a validation error

  # ----- Settings + slot configuration (green) -----

  @smoke
  # HH-10 / HH-11: a fresh household exposes its default settings + resolved slot
  # configuration (breakfast/lunch/dinner/snack), with an empty settings audit-log.
  Scenario: A fresh household exposes default settings and slot configuration
    Given a fresh registered and logged-in user
    And the user has created a household
    When they read their household settings
    Then the household settings carry the four default meal slots for this household
    When they read their household slot configuration
    Then the slot configuration lists the default meal slots for this household
    When they read their household settings audit log
    Then the household settings audit log is empty for this household

  # HH-11: the primary edits a slot setting (dinner -> not shared); the change is
  # reflected on a read and an audit row is written.
  Scenario: The primary edits a slot setting and the change is audited
    Given a fresh registered and logged-in user
    And the user has created a household
    When they mark the dinner slot as not shared
    Then the slot setting change is reflected on a read for this household
    And the household settings audit log records the change for this household

  # HH-11 (optimistic lock): a settings PUT with a stale expectedVersion is a 409.
  Scenario: A settings update with a stale version is rejected as a conflict
    Given a fresh registered and logged-in user
    And the user has created a household
    When they update settings with a stale expected version
    Then the settings update is rejected as a conflict

  # ----- Membership (green: second member assembled via a separate registration) -----

  @smoke
  # HH-02: the primary directly adds a second registered account as a member; the
  # roster grows to two and the union/merge inputs now span both.
  Scenario: The primary adds a second member to the household
    Given a fresh registered and logged-in user
    And the user has created a household
    And a second user account exists
    When they add the second user as a member
    Then the household roster includes the second member for this household

  # HH-04: the primary removes the added member; the roster shrinks back to one.
  Scenario: The primary removes an added member
    Given a fresh registered and logged-in user
    And the user has created a household
    And a second user account exists
    And the primary has added the second user as a member
    When they remove the second member
    Then the household roster no longer includes the second member for this household

  # HH-09: demoting the sole primary while another member remains is a 409
  # (the ">=1 primary always" invariant, enforced as LastPrimaryRemoval).
  Scenario: Demoting the last primary while another member remains is rejected
    Given a fresh registered and logged-in user
    And the user has created a household
    And a second user account exists
    And the primary has added the second user as a member
    When they demote themselves while another member remains
    Then the role change is rejected as a conflict

  # ----- Errors (green) -----

  # HH-10 (not-found): reading settings for an unknown household id is a clean 404.
  Scenario: Reading settings for a non-existent household returns not found
    Given a fresh registered and logged-in user
    When they read settings for a random non-existent household
    Then the household settings read is rejected as not found

  # HH cross-cutting: creating a household with no session is denied.
  Scenario: Creating a household with no session is denied
    Given a fresh registered and logged-in user
    When an anonymous client creates a household with no session
    Then the request is rejected as unauthenticated

  # ----- @pending: invite handshake + merge need state we cannot assemble cleanly -----

  @pending
  # HH-05 (invite/accept handshake). PENDING: the accept leg (POST /api/v1/invites/accept)
  # must run on the INVITED user's session, but a scenario holds one cookie jar at a
  # time; accepting on a second session and then asserting roster state on the primary's
  # session needs a two-session orchestration the harness's single-ApiClient-per-scenario
  # model does not yet express. Un-pend when multi-session scenarios are supported.
  Scenario: A user accepts an invite and joins the household
    Given a fresh registered and logged-in user
    And the user has created a household
    And a second user account exists
    When the primary creates an invite for the second user
    And the second user accepts that invite
    Then the household roster includes the second member for this household

  @pending
  # HH-20/HH-21 (soft-preference merge / irreconcilable-union split). PENDING: the merge
  # weighting is the domain's central HLD-GAP (deferred to a non-existent Household Model
  # design), and the split is owned by the Planner's constraint resolution off a non-empty
  # recipe pool (which the e2e stack lacks — see planner.feature). Only weak invariants are
  # specifiable; deferred until the merge algorithm + a recipe pool exist.
  Scenario: A shared slot with conflicting constraints is split into individual meals
    Given a fresh registered and logged-in user
    And the user has created a household
    When they read their household slot configuration
    Then the slot configuration lists the default meal slots for this household
