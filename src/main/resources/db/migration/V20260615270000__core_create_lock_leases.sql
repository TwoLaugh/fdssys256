-- core_lock_leases: connection-free, TTL-based lease lock backing
-- LockService.acquireLease / releaseLease / renewLease.
--
-- Why a committed lease row (not a held advisory lock):
-- Plan generation runs a ~20s AI pipeline that MUST NOT hold a DB connection across the
-- LLM latency (the constraint PR #193 fixed). A session-scoped pg_advisory_lock held from
-- generation-start would re-pin a connection for the whole pipeline. Instead, a short
-- transaction INSERTs a committed lease row (connection released the instant it commits);
-- the row's existence + the PRIMARY KEY on lock_key enforces single-flight, and the
-- expires_at column makes a crashed holder's lease reclaimable (lazy reclaim-on-acquire).
--
-- This is an ADDITION: the transaction-scoped pg_try_advisory_xact_lock used by adaptation
-- and grocery (no table) is unaffected. Per lld/core.md §LockService.

CREATE TABLE core_lock_leases (
    lock_key     varchar(160) PRIMARY KEY,
    holder_token uuid        NOT NULL,
    acquired_at  timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL
);

-- Lazy reclaim-on-acquire filters expired leases by expires_at; the index keeps the
-- reclaim predicate cheap should the table accumulate stale rows before a sweep.
CREATE INDEX idx_core_lock_leases_expires_at
    ON core_lock_leases (expires_at);
