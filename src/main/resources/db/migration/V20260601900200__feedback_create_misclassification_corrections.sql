-- Feedback module — 01a ground-truth dataset: feedback_misclassification_corrections.
-- See lld/feedback.md §V20260501130200. Renumbered to V20260601900200.
--
-- Append-only audit of user-driven corrections. Copies original_destination /
-- original_confidence at correction time so a future fine-tune pipeline can pull
-- labelled examples without joining back to the routing log.
--
-- replay_status admits four values per LLD divergence note in ticket 01a:
-- PENDING_REPLAY | APPLIED | FAILED | DESTINATION_REJECTED. The column is NOT NULL;
-- the initial insert sets PENDING_REPLAY, the replayer (feedback-01f) flips it to a
-- terminal value. created_at added defensively per style-guide §Entities (LLD omits).

CREATE TABLE feedback_misclassification_corrections (
    id                       uuid          PRIMARY KEY,
    feedback_entry_id        uuid          NOT NULL REFERENCES feedback_entries(id) ON DELETE CASCADE,
    original_routing_id      uuid          NOT NULL REFERENCES feedback_routing_log(id),
    corrected_destination    varchar(16)   NOT NULL,                 -- RECIPE | PREFERENCE | NUTRITION | PROVISIONS
    user_correction_note     varchar(512),                           -- optional free text
    actor_user_id            uuid          NOT NULL,                 -- who did the correction
    original_confidence      numeric(4,3)  NOT NULL,                 -- copied for ground-truth labelling
    original_destination     varchar(16)   NOT NULL,                 -- copied likewise
    replay_routing_id        uuid          REFERENCES feedback_routing_log(id),
                                                                     -- set after the corrected destination runs
    replay_status            varchar(24)   NOT NULL,                 -- PENDING_REPLAY | APPLIED | FAILED |
                                                                     --  DESTINATION_REJECTED
    occurred_at              timestamptz   NOT NULL,
    created_at               timestamptz   NOT NULL
);

-- Quality monitoring dataset: correction-rate metric, ground-truth export.
CREATE INDEX idx_misclassification_routing
    ON feedback_misclassification_corrections (original_routing_id);

CREATE INDEX idx_misclassification_entry_time
    ON feedback_misclassification_corrections (feedback_entry_id, occurred_at DESC);
