-- Feedback module — 01a aggregate root: feedback_entries.
-- See lld/feedback.md §V20260501130000. Renumbered to V20260601900000 to sequence
-- after recipe (V…1800xxx) and reserve room before wave-3 siblings.
--
-- Aggregate root for a user's free-text feedback submission. ui_context is JSONB
-- (shape mirrored by UiContextDocument). No cross-module FK on user_id — modules
-- store opaque UUIDs only.

CREATE TABLE feedback_entries (
    id                       uuid          PRIMARY KEY,
    user_id                  uuid          NOT NULL,
    text                     text          NOT NULL,                 -- raw user input; never truncated
    ui_context               jsonb         NOT NULL,                 -- shape mirrored by UiContextDocument
    submission_status        varchar(24)   NOT NULL,                 -- RECEIVED | CLASSIFYING | CLASSIFIED |
                                                                     --  CLARIFICATION_PENDING | ROUTED |
                                                                     --  PARTIALLY_FAILED | FAILED | CORRECTED
    classification_attempts  integer       NOT NULL DEFAULT 0,       -- counts re-classifications after clarification
    last_classified_at       timestamptz,                            -- null until first classification completes
    trace_id                 uuid          NOT NULL,                 -- propagates to ai_call_log + downstream events
    optimistic_version       bigint        NOT NULL DEFAULT 0,       -- @Version
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL
);

-- User-history page on the feedback timeline view.
CREATE INDEX idx_feedback_entries_user_created
    ON feedback_entries (user_id, created_at DESC);

-- Async classifier sweep finds unstarted submissions if the in-process publish failed.
CREATE INDEX idx_feedback_entries_status_created
    ON feedback_entries (submission_status, created_at)
    WHERE submission_status IN ('RECEIVED', 'CLASSIFYING');

-- Trace lookup on cross-module debugging.
CREATE INDEX idx_feedback_entries_trace
    ON feedback_entries (trace_id);
