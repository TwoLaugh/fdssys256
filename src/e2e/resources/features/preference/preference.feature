@preference
Feature: Preference — three-tier reads, not-found-before-seed errors, empty-collection reads
  The Preference domain is the three data models the system optimises against
  (see e2e/pathways/preference.md): Tier-1 hard constraints (user-only,
  deterministic), the Tier-2 AI-maintained taste profile (+ unbounded archive),
  and the Tier-3 lifestyle config. This wave-2 feature exercises every path that
  is buildable GREEN over HTTP from a fresh user, and pins down the load-bearing
  error/empty behaviours; the seed-then-mutate happy paths are @pending below
  (with the one-line reason) because no HTTP path seeds a preference aggregate.

  Each scenario registers its OWN fresh user (D5 self-contained data) and asserts
  only on THIS user's preference state (self-scoped) — never global counts — so the
  feature runs in both clean and soak mode.

  # ----- The load-bearing finding (drives the @pending set) -----
  # A fresh, just-registered user has NO preference aggregates and there is NO
  # HTTP path to create one. initialise() / initialiseHardConstraints() /
  # LifestyleConfig.initialise() exist ONLY as in-process service calls
  # (PreferenceServiceImpl / TasteProfileServiceImpl / LifestyleConfigServiceImpl);
  # registration publishes UserRegisteredEvent but NO preference listener consumes
  # it to seed, and the only e2e test-support endpoint is the AI stub (no seeder).
  # So GET/PUT on each aggregate, refresh-now, and rollback all 404 for a fresh
  # user (mirrors the Wave-1 nutrition "PUT 404s when no row exists" lesson). The
  # version/audit-log/archive LIST endpoints instead return an empty Page (200).

  @smoke
  # PREF-12: reading a taste profile before one is seeded is a clean not-found.
  Scenario: Reading the taste profile before it is seeded returns not found
    Given a fresh registered and logged-in user
    When they read their taste profile
    Then the taste-profile read is rejected as not found

  @smoke
  # PREF-06 precondition: reading hard constraints before onboarding seeds them is not-found.
  Scenario: Reading hard constraints before they are seeded returns not found
    Given a fresh registered and logged-in user
    When they read their hard constraints
    Then the hard-constraints read is rejected as not found

  # PREF-06 (error slice): a hard-constraints PUT 404s when no aggregate exists —
  # the tier cannot be created over HTTP (initialise is in-process only).
  Scenario: Setting hard constraints before the aggregate exists is rejected as not found
    Given a fresh registered and logged-in user
    When they set an allergy on their hard constraints
    Then the hard-constraints update is rejected as not found

  # PREF-26 (error slice): reading lifestyle config before initialise is not-found.
  Scenario: Reading lifestyle config before it is initialised returns not found
    Given a fresh registered and logged-in user
    When they read their lifestyle config
    Then the lifestyle-config read is rejected as not found

  # PREF-26 (error slice): a lifestyle PUT 404s until initialise has run (initialise
  # is the onboarding-wizard's in-process call, never exposed on the REST surface).
  Scenario: Editing lifestyle config before it is initialised is rejected as not found
    Given a fresh registered and logged-in user
    When they edit a lifestyle config setting
    Then the lifestyle-config update is rejected as not found

  # PREF-15 (error slice): a manual refresh-now 404s with no profile to refresh.
  Scenario: Requesting a manual taste-profile refresh before a profile exists is rejected as not found
    Given a fresh registered and logged-in user
    When they request a manual taste-profile refresh
    Then the taste-profile refresh is rejected as not found

  @smoke
  # PREF-23 (empty slice): the version history list is an empty page for a fresh user
  # (the endpoint returns Page.empty rather than 404 when no profile exists).
  Scenario: Taste-profile version history is empty for a fresh user
    Given a fresh registered and logged-in user
    When they list their taste-profile versions
    Then the taste-profile version list is empty for this user

  # PREF-06 (audit slice): the hard-constraints audit log is an empty page for a fresh user.
  Scenario: Hard-constraints audit log is empty for a fresh user
    Given a fresh registered and logged-in user
    When they read their hard-constraints audit log
    Then the hard-constraints audit log is empty for this user

  # PREF-32 (empty slice): the lifestyle-config audit log is an empty page for a fresh user.
  Scenario: Lifestyle-config audit log is empty for a fresh user
    Given a fresh registered and logged-in user
    When they read their lifestyle-config audit log
    Then the lifestyle-config audit log is empty for this user

  @smoke
  # PREF-25 (empty variation): the preference archive is empty for a fresh user (nothing pruned).
  Scenario: The preference archive is empty for a fresh user
    Given a fresh registered and logged-in user
    When they read their preference archive
    Then the preference archive is empty for this user

  # PREF-25 / PREF-19: the active archive count is zero for a fresh user.
  Scenario: The active archive count is zero for a fresh user
    Given a fresh registered and logged-in user
    When they read their active archive count
    Then the active archive count is zero for this user

  # PREF cross-cutting: a protected preference read with no session is denied before domain logic.
  Scenario: Reading hard constraints with no session is denied
    Given a fresh registered and logged-in user
    When an anonymous client reads hard constraints with no session
    Then the request is rejected as unauthenticated

  @pending
  # PREF-06 — Add an allergy (happy: PUT -> GET reflects it -> audit-log records it).
  # PENDING: a fresh user has no hard-constraints aggregate and there is NO HTTP path to
  # create one (initialiseHardConstraints is in-process only; registration seeds nothing).
  # The PUT 404s. Drop @pending once an onboarding/initialise HTTP endpoint (or e2e seeder) lands.
  Scenario: A user adds an allergy and sees it reflected with an audit entry
    Given a fresh registered and logged-in user
    When they set an allergy on their hard constraints
    Then the allergy is stored and reflected on a read for this user
    And the hard-constraints audit log records the allergy change for this user

  @pending
  # PREF-08 — Set a structured dietary identity (base + exception).
  # PENDING: same root cause — no HTTP path seeds the hard-constraints aggregate, so the PUT 404s.
  Scenario: A user sets a structured dietary identity with an exception
    Given a fresh registered and logged-in user
    When they set a structured dietary identity on their hard constraints
    Then the dietary identity is stored and reflected on a read for this user

  @pending
  # PREF-13 — Manually override a taste-profile item.
  # PENDING: a fresh user has no taste profile (initialise is in-process only), so the override PUT 404s.
  Scenario: A user manually overrides a learned taste-profile item
    Given a fresh registered and logged-in user
    When they manually override a taste-profile item
    Then the override creates a new taste-profile version for this user

  @pending
  # PREF-26 — Edit a lifestyle-config setting (happy: PUT -> GET reflects it -> audit-log).
  # PENDING: lifestyle config is never created over HTTP (initialise is the onboarding wizard's
  # in-process call), so the edit PUT 404s for a fresh user.
  Scenario: A user edits a lifestyle setting and sees an audit entry
    Given a fresh registered and logged-in user
    When they edit a lifestyle config setting
    Then the lifestyle setting is stored and reflected on a read for this user
    And the lifestyle-config audit log records the change for this user

  @pending
  # PREF-30/31 — Acknowledge a lifestyle review nudge (mark-reviewed resets the prompt timestamp).
  # PENDING: mark-reviewed needs an existing lifestyle config, which cannot be created over HTTP.
  Scenario: A user acknowledges a lifestyle review nudge
    Given a fresh registered and logged-in user
    When they mark their lifestyle config as reviewed
    Then the review nudge is reset for this user

  @pending
  # PREF-24 — Roll the taste profile back to a prior version.
  # PENDING: needs >=2 reachable versions; both seeding the profile and reaching a 2nd version require
  # the AI delta pipeline over a profile that cannot be created over HTTP (no initialise endpoint).
  Scenario: A user rolls the taste profile back to a prior version
    Given a fresh registered and logged-in user
    When they roll their taste profile back to version 1
    Then the rollback creates a new taste-profile version for this user

  @pending
  # PREF-38 (flagship, the AI keystone proof) — feedback-driven delta update: prime a realistic
  # PREFERENCE_DELTA_UPDATE (ADD prawns to likes.ingredients) -> POST refresh-now -> poll for the
  # version bump -> assert the new version applied the delta AND Tier-1 hard constraints are unchanged.
  # PENDING: the whole journey needs a pre-existing taste profile (and hard constraints) to refresh,
  # and a fresh user has neither over HTTP (initialise is in-process only; refresh-now itself 404s
  # without a profile). The realistic canned JSON + the full async poll glue are written below so the
  # wire-contract and timing are ready the moment an HTTP seed path exists. Drop @pending then.
  Scenario: An AI delta update adds a liked ingredient while hard constraints stay untouched
    Given a fresh registered and logged-in user
    And the AI will return this PREFERENCE_DELTA_UPDATE response:
      """
      {
        "deltas": [
          {
            "type": "Add",
            "fieldPath": "likes.ingredients",
            "item": "prawns",
            "notes": "especially in quick high-heat preparations",
            "evidenceFeedbackId": "f1e2d3c4-0000-4000-8000-000000000001",
            "reasoning": "single explicit positive statement about prawns",
            "confidence": "MEDIUM"
          }
        ],
        "overallReasoning": "added prawns to likes from explicit feedback",
        "warnings": []
      }
      """
    When they request a manual taste-profile refresh
    Then the taste profile reaches a new version after the refresh for this user
    And the user's hard constraints are unchanged for this user
