-- Feedback module — 01a clarification queue: feedback_clarification_queries.
-- See lld/feedback.md §V20260501130300. Renumbered to V20260601900300.
--
-- The <0.5-confidence path persists a question for the user; the user's response
-- either selects a destination or types a clarification, then the classifier re-runs.
-- 7-day TTL avoids an ever-growing inbox. Expiry sweep + answer flow land in 01e.

CREATE TABLE feedback_clarification_queries (
    id                       uuid          PRIMARY KEY,
    feedback_entry_id        uuid          NOT NULL REFERENCES feedback_entries(id) ON DELETE CASCADE,
    classifier_options_json  jsonb         NOT NULL,                 -- shortlist [{destination, snippet, classifierJustification}]
    question_text            varchar(512)  NOT NULL,                 -- canned text + classifier's framing
    status                   varchar(24)   NOT NULL,                 -- PENDING | ANSWERED | EXPIRED
    selected_destination     varchar(16),                            -- null until answered
    user_clarification_text  text,                                   -- optional free-text addition; appended on re-classify
    expires_at               timestamptz   NOT NULL,                 -- 7-day TTL by default
    answered_at              timestamptz,
    optimistic_version       bigint        NOT NULL DEFAULT 0,       -- @Version
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL
);

-- User-facing inbox: open clarifications for this user, oldest first.
CREATE INDEX idx_clarification_user_status_created
    ON feedback_clarification_queries (status, created_at)
    WHERE status = 'PENDING';

-- Daily expiry sweep.
CREATE INDEX idx_clarification_expires
    ON feedback_clarification_queries (expires_at)
    WHERE status = 'PENDING';
