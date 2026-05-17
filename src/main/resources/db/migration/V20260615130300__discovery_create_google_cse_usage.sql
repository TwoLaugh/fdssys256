-- Discovery — Google CSE daily quota tracker (01e).
-- One row per UTC day. Survives restart so a frequently-restarting deploy doesn't lose quota
-- state and overrun the 100/day free tier. Reset point is UTC midnight; the application code
-- (GoogleCseDailyQuotaTracker) rolls the in-memory counter over and persists the prior day.
-- The PK on `day` is the only index needed (one-row-per-day, point reads).
-- LLD divergence (worth user review): not in lld/discovery.md — operationally necessary for
-- Google CSE quota tracking; alternative is in-memory-only (loses count on restart).

CREATE TABLE discovery_google_cse_usage (
    day         date PRIMARY KEY,
    call_count  integer NOT NULL DEFAULT 0,
    updated_at  timestamptz NOT NULL
);
