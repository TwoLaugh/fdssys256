-- decision_log: append-only audit table for every iteration of an optimisation loop
-- and any other audit-worthy decision points across the system.
-- Per lld/optimisation-loop.md and lld/core.md.
--
-- Columns scope_kind, scope_id, actor_user_id are lifted from the JSONB inputs blob
-- per the locked decision in lld/core.md, so the most common cross-trace queries
-- ("all decisions for this week, this user") use indexed columns rather than JSONB
-- operators in WHERE clauses.

CREATE TABLE decision_log (
    decision_id        uuid PRIMARY KEY,
    trace_id           uuid NOT NULL,
    parent_decision_id uuid REFERENCES decision_log(decision_id),
    scope_kind         varchar(32) NOT NULL,
    scope_id           uuid NOT NULL,
    scale              varchar(16) NOT NULL,
    triggered_by       varchar(32) NOT NULL,
    actor_user_id      uuid,
    inputs             jsonb NOT NULL,
    candidates         jsonb,
    chosen             jsonb,
    reasoning          text,
    emitted_directive  jsonb,
    iteration          integer NOT NULL DEFAULT 0,
    duration_ms        integer,
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- Hot read: walking a trace ordered by time. Used by getByTraceId and the
-- recursive ancestry CTE.
CREATE INDEX idx_decision_log_trace_created
    ON decision_log (trace_id, created_at);

-- Common admin query: "all decisions for this scope (e.g. plan-week of a household)
-- ordered newest-first". Lifted columns let this avoid JSONB operators.
CREATE INDEX idx_decision_log_scope_created
    ON decision_log (scope_kind, scope_id, created_at DESC);

-- "What did this user trigger recently?" — partial index keeps system-initiated
-- rows (where actor_user_id is null) out of the index.
CREATE INDEX idx_decision_log_actor_created
    ON decision_log (actor_user_id, created_at DESC)
    WHERE actor_user_id IS NOT NULL;

-- Ancestry walk uses parent_decision_id; partial keeps roots out of the index.
CREATE INDEX idx_decision_log_parent
    ON decision_log (parent_decision_id)
    WHERE parent_decision_id IS NOT NULL;
