-- Feedback module — 01g destination bridges: feedback_bridge_idempotency.
-- See tickets/feedback/01g-destination-bridges.md §4 and design/origin-tracking-pattern.md
-- §Recursion guard ("AI-originated mutations include an idempotency key").
--
-- The four real destination bridges (preference, nutrition, provisions, recipe) book one row per
-- (feedback_id, destination) when they dispatch classifier output downstream. The UNIQUE constraint
-- enforces idempotency at the DB level: re-processing the same feedback within the 5-minute window
-- is a no-op (insert-or-skip via ON CONFLICT DO NOTHING in the bridge code). Rows persist longer
-- (default 7 days, see FeedbackBridgeIdempotencyCleanupScheduler) for forensic / audit visibility.

CREATE TABLE feedback_bridge_idempotency (
    id                       uuid          PRIMARY KEY,
    feedback_id              uuid          NOT NULL,
    destination              varchar(16)   NOT NULL,                  -- PREFERENCE | NUTRITION | PROVISIONS | RECIPE
    status                   varchar(32)   NOT NULL,                  -- DISPATCHED | REJECTED_LOW_CONFIDENCE | FAILED
    dispatched_at            timestamptz   NOT NULL,
    UNIQUE (feedback_id, destination)
);

-- Recent-window probe + retention sweep both scan dispatched_at; index it DESC.
CREATE INDEX idx_feedback_bridge_idempotency_recent
    ON feedback_bridge_idempotency (dispatched_at DESC);
