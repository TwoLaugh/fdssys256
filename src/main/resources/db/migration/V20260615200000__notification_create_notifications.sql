CREATE TABLE notifications (
    id                    uuid PRIMARY KEY,
    user_id               uuid NOT NULL,
    household_id          uuid,                         -- populated for household-scoped kinds, null for per-user
    kind                  varchar(64) NOT NULL,         -- NotificationKind enum
    severity              varchar(16) NOT NULL,         -- INFO | ATTENTION | URGENT
    title                 varchar(200) NOT NULL,
    body                  varchar(1000) NOT NULL,
    payload               jsonb NOT NULL,               -- kind-specific NotificationPayload
    status                varchar(16) NOT NULL,         -- UNREAD | READ | DISMISSED | ACTIONED
    action_target_uri     varchar(512),                 -- deep link
    bundle_count          integer NOT NULL DEFAULT 1,   -- > 1 if this row absorbed siblings
    bundle_keys           jsonb,                        -- ids of bundled origins
    source_event_id       uuid,
    trace_id              uuid,
    created_at            timestamptz NOT NULL,
    read_at               timestamptz,
    actioned_at           timestamptz,
    dismissed_at          timestamptz,
    optimistic_version    bigint NOT NULL DEFAULT 0     -- @Version
);
-- Inbox listing.
CREATE INDEX idx_notifications_user_status_created ON notifications (user_id, status, created_at DESC);
-- Debouncer lookup of open rows for (user, kind) within the dedup window.
CREATE INDEX idx_notifications_user_kind_created ON notifications (user_id, kind, created_at DESC);
-- Cheap unread badge count.
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE status = 'UNREAD';
