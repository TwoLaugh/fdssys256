@feedback
Feature: Feedback — submit, async classify, route/clarify/store, recent-history, and errors
  The Feedback System is the conversational front door: it classifies free text
  with a cheap AI and routes each slice to a destination (recipe/preference/
  nutrition/provisions) — see e2e/pathways/feedback.md. This Batch-3 feature
  exercises every path that is buildable GREEN over HTTP from a fresh user and
  marks the rest @pending with a precise one-line reason each.

  The load-bearing findings that shape this feature (from reading FeedbackController,
  FeedbackClassificationListener, FeedbackRouterImpl, ConfidenceGate, the destination
  dispatchers/bridges, and FeedbackClassificationFlowIT):
  - SUBMIT IS ASYNC. POST /api/v1/feedback returns 202 with the entry in RECEIVED
    state; classification runs on an AFTER_COMMIT @Async listener. The terminal
    submissionStatus (ROUTED / CLARIFICATION_PENDING / PARTIALLY_FAILED / FAILED)
    is observed by POLLING GET /api/v1/feedback/{id} (mirrors the IT's awaitState).
  - CLASSIFICATION CROSSES THE AI. Every submit dispatches FEEDBACK_CLASSIFICATION;
    an unprimed/invalid response makes the entry terminal-FAIL (the listener catches
    AiInvalidResponse and marks FAILED — it does NOT 502 the async path). So we PRIME
    the e2e AI stub with a realistic ClassificationResult before each submit.
    Shape: { "classifications":[ { "destination":..., "confidence":..,
    "extractedFeedback":"..", "structuredPayload":{..} } ], "overallConfidence":..,
    "classifierNotes":null }.
  - CONFIDENCE GATE (locked): >=0.8 AUTO_ROUTED; [0.5,0.8) ROUTED_WITH_FLAG; any
    classification <0.5 pauses the WHOLE entry for clarification (CLARIFICATION_PENDING
    + a clarification-query row). Empty classifications -> ROUTED with zero routes.
  - ROUTING FAILS GRACEFULLY, NEVER 502s. A destination whose aggregate is missing
    for a fresh user (e.g. PREFERENCE -> no taste profile) records a FAILED routing
    row, not an HTTP error. The PROVISIONS MARK_DEPLETED action is idempotent and a
    no-op SUCCESS when the user owns no matching inventory — so it routes to APPLIED
    for a fresh user with no seeding (the one clean submit->APPLIED green path).
  - E2eFeedbackSeedController (already in main) persists one PREFERENCE-routed,
    APPLIED FeedbackEntry for the caller — used to assert a routed-entry read without
    depending on async timing.

  Each scenario registers its OWN fresh user (D5 self-contained data) and asserts
  only on THIS user's feedback entries (self-scoped) — never global counts — so the
  feature runs in both clean and soak mode.

  # ----- Submit -> async classify -> terminal state (green, AI primed) -----

  @smoke
  # FEED-04 happy path with a clean APPLIED route: a high-confidence PROVISIONS
  # MARK_DEPLETED classification routes to APPLIED even for a fresh user (the bridge
  # treats "out of something you don't track" as an idempotent no-op success).
  Scenario: A user submits feedback that classifies and routes to a destination
    Given a fresh registered and logged-in user
    And the AI will classify the next feedback to provisions at high confidence
    When they submit feedback "I'm out of soy sauce"
    Then the feedback submission is accepted for processing
    And the feedback entry eventually reaches a routed state for this user
    And the feedback entry has a routing decision to provisions for this user

  @smoke
  # FEED-13: feedback the classifier deems non-actionable (empty classifications) is
  # still STORED, with zero routes, and the entry settles in ROUTED.
  Scenario: Non-actionable feedback is stored with no routes
    Given a fresh registered and logged-in user
    And the AI will classify the next feedback as non-actionable
    When they submit feedback "thanks, this app is great!"
    Then the feedback submission is accepted for processing
    And the feedback entry eventually reaches a routed state for this user
    And the feedback entry has no routing decisions for this user

  # FEED-10: a low-confidence classification (<0.5) pauses the whole entry for
  # clarification — no destination write, a clarification query is opened.
  Scenario: Low-confidence feedback opens a clarification query
    Given a fresh registered and logged-in user
    And the AI will classify the next feedback at low confidence
    When they submit feedback "it was a bit much"
    Then the feedback submission is accepted for processing
    And the feedback entry eventually awaits clarification for this user
    And a clarification query is listed for this user

  # ----- Validation + auth (green, synchronous) -----

  # FEED-14: blank text is rejected synchronously by bean validation (@NotBlank) —
  # 400, before any classifier call (no entry created).
  Scenario: Submitting blank feedback text is rejected as a validation error
    Given a fresh registered and logged-in user
    When they submit feedback with blank text
    Then the feedback submission is rejected as a validation error

  # FEED-14 (oversize slice): text beyond the 4000-char @Size cap is a 400.
  Scenario: Submitting oversized feedback text is rejected as a validation error
    Given a fresh registered and logged-in user
    When they submit feedback text that exceeds the length limit
    Then the feedback submission is rejected as a validation error

  # FEED cross-cutting: submitting with no session is denied before any feedback logic.
  Scenario: Submitting feedback with no session is denied
    Given a fresh registered and logged-in user
    When an anonymous client submits feedback with no session
    Then the request is rejected as unauthenticated

  # ----- Reads (green: empty-state + seeded routed entry) -----

  @smoke
  # FEED-17 (cold start): a fresh user's recent-feedback list is an empty page.
  Scenario: Recent feedback is empty for a fresh user
    Given a fresh registered and logged-in user
    When they list their recent feedback
    Then the recent feedback list is empty for this user

  # FEED-16 / FEED-17: a seeded PREFERENCE-routed entry is readable by id and in the
  # list, carrying its APPLIED preference route (uses the e2e seeder — no async wait).
  Scenario: A routed feedback entry is readable with its routing log
    Given a fresh registered and logged-in user
    And the user has a seeded preference-routed feedback entry
    When they read that feedback entry by id
    Then the feedback entry read returns an applied preference route for this user
    And the seeded feedback entry appears in their recent feedback for this user

  # FEED read (not-found): reading an unknown feedback id is a clean 404.
  Scenario: Reading a non-existent feedback entry returns not found
    Given a fresh registered and logged-in user
    When they read a feedback entry by a random non-existent id
    Then the feedback read is rejected as not found

  # FEED corrections (cold start): the corrections ground-truth list is empty.
  Scenario: The corrections list is empty for a fresh user
    Given a fresh registered and logged-in user
    When they list their feedback corrections
    Then the feedback corrections list is empty for this user

  # FEED clarifications (cold start): the clarification queue is empty.
  Scenario: The clarification queue is empty for a fresh user
    Given a fresh registered and logged-in user
    When they list their clarification queue
    Then the clarification queue is empty for this user

  # ----- Correction + clarification answer (green, multi-step over HTTP) -----

  # FEED-19/20 (correct a misrouted route). The e2e seeder persists a real APPLIED
  # PREFERENCE route; per ticket 01f a route is correctable in ANY non-terminal-correction
  # state (only CORRECTED_AWAY / REPLAYED are rejected, plus the RECIPE structural guard),
  # so an APPLIED PREFERENCE route corrects cleanly to NUTRITION over HTTP. The correction
  # row is recorded regardless of the synthetic replay's destination outcome (the replay
  # runs in its own REQUIRES_NEW tx; a fresh-user NUTRITION replay booking FAILED still
  # leaves a 200 + a recorded ground-truth correction). The PREFERENCE reverter is a
  # log-only Noop (01h not wired) — best-effort undo, never blocks the correction.
  Scenario: A user corrects a misrouted route to another destination
    Given a fresh registered and logged-in user
    And the user has a seeded preference-routed feedback entry
    When they correct that route to nutrition
    Then the correction is recorded alongside the original route for this user

  # FEED-11 (answer a clarification -> reclassify -> route). The answer re-fires the async
  # classifier on the SAME entry (same traceId), which calls the AI again — the user's
  # selected destination is only a prompt hint, the route is decided by the second model
  # response. The e2e AI stub keys one canned response per TaskType, so re-priming
  # FEEDBACK_CLASSIFICATION (overwrite) between the two classifications gives the second
  # pass a fresh high-confidence result deterministically — no stub sequencing needed. The
  # second pass classifies to PROVISIONS MARK_DEPLETED (the clean fresh-user APPLIED path),
  # so the entry settles ROUTED. (A PREFERENCE re-route would FAIL for a fresh user — no
  # taste profile — and never reach ROUTED, so the answer chooses provisions to match.)
  Scenario: Answering a clarification reclassifies and routes the entry
    Given a fresh registered and logged-in user
    And the AI will classify the next feedback at low confidence
    When they submit feedback "it was a bit much"
    Then the feedback submission is accepted for processing
    And the feedback entry eventually awaits clarification for this user
    And the AI will reclassify the answered clarification to provisions at high confidence
    When they answer that clarification choosing provisions
    Then the feedback entry eventually reaches a routed state for this user
