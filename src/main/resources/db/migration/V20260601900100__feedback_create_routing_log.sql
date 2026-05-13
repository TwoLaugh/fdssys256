-- Feedback module — 01a routing audit trail: feedback_routing_log.
-- See lld/feedback.md §V20260501130100. Renumbered to V20260601900100.
--
-- One row per (feedback, destination) pair. Append-only-with-status-update: status,
-- action_taken, destination_result_json, completed_at, superseded_by are the only
-- fields ever updated post-insert. No @Version — concurrency by single-writer-per-row.
--
-- failure_kind admits the six Java enum values per LLD divergence note in ticket 01a:
-- TRANSIENT | DESTINATION_VALIDATION | DESTINATION_BUSINESS | AI_UNAVAILABLE | UNKNOWN.
-- (LLD line 121 comment listed only four; the migration ships with the full six since
-- there's no DB-level CHECK constraint and the Java enum is the authoritative whitelist.)

CREATE TABLE feedback_routing_log (
    id                       uuid          PRIMARY KEY,
    feedback_entry_id        uuid          NOT NULL REFERENCES feedback_entries(id) ON DELETE CASCADE,
    destination              varchar(16)   NOT NULL,                 -- RECIPE | PREFERENCE | NUTRITION | PROVISIONS
    confidence               numeric(4,3)  NOT NULL,                 -- 0.000 .. 1.000
    extracted_feedback       text          NOT NULL,                 -- the slice the classifier attributed to this destination
    structured_payload       jsonb         NOT NULL,                 -- destination-specific structured fields from the classifier
    routing_decision         varchar(24)   NOT NULL,                 -- AUTO_ROUTED | ROUTED_WITH_FLAG | CLARIFICATION_QUEUED
    status                   varchar(24)   NOT NULL,                 -- PENDING | APPLIED | FAILED |
                                                                     --  CORRECTED_AWAY | REPLAYED | AWAITING_USER_APPROVAL
    action_taken             varchar(512),                           -- human-readable summary returned by the destination
    destination_result_json  jsonb,                                  -- destination-typed result shell (e.g. AdaptationResult)
    failure_kind             varchar(32),                            -- TRANSIENT | DESTINATION_VALIDATION |
                                                                     --  DESTINATION_BUSINESS | AI_UNAVAILABLE | UNKNOWN
    failure_message          varchar(512),                           -- truncated; never the API key
    superseded_by            uuid          REFERENCES feedback_routing_log(id) ON DELETE SET NULL,
                                                                     -- set on the original row when a correction replays
    classification_attempt   integer       NOT NULL,                 -- which attempt (1-indexed) produced this routing
    routed_at                timestamptz   NOT NULL,
    completed_at             timestamptz,                            -- when status moved to terminal
    created_at               timestamptz   NOT NULL
);

-- Confirmation view: list every routing for a given entry, in routing order.
CREATE INDEX idx_routing_log_entry_routed
    ON feedback_routing_log (feedback_entry_id, routed_at);

-- Quality monitoring: distribution by destination + confidence over time.
CREATE INDEX idx_routing_log_dest_confidence
    ON feedback_routing_log (destination, routed_at DESC);

-- Failure dashboards.
CREATE INDEX idx_routing_log_status
    ON feedback_routing_log (status, routed_at DESC)
    WHERE status IN ('FAILED', 'PENDING');
