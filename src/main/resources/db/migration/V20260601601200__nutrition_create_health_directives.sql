-- V20260601601200 — Health-directive queue (LLD §V20260502120700 lines 294-324).
-- Standalone aggregate root; UNIQUE on (source_platform, external_directive_id) for idempotent
-- inbound; JSONB columns for instruction_payload / safety_gate_findings / user_modification_json.
CREATE TABLE nutrition_health_directives (
    id                              uuid PRIMARY KEY,
    user_id                         uuid NOT NULL,
    external_directive_id           varchar(128) NOT NULL,
    source_platform                 varchar(64) NOT NULL,
    received_at                     timestamptz NOT NULL,
    status                          varchar(24) NOT NULL DEFAULT 'PENDING_REVIEW',
    directive_type                  varchar(48) NOT NULL,
    evidence_summary                text,
    evidence_confidence             varchar(16),
    instruction_payload             jsonb NOT NULL,
    maps_to_model                   varchar(24) NOT NULL,
    maps_to_tier                    varchar(48),
    temporary                       boolean NOT NULL DEFAULT true,
    auto_expires_at                 timestamptz,
    decided_at                      timestamptz,
    decided_by_user_id              uuid,
    user_modification_json          jsonb,
    rejection_reason                varchar(255),
    safety_gate_verdict             varchar(16),
    safety_gate_findings            jsonb,
    optimistic_version              bigint NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL,
    updated_at                      timestamptz NOT NULL,
    UNIQUE (source_platform, external_directive_id)
);
CREATE INDEX idx_nutr_directives_user_status
    ON nutrition_health_directives (user_id, status, received_at DESC);
CREATE INDEX idx_nutr_directives_auto_expires
    ON nutrition_health_directives (auto_expires_at) WHERE auto_expires_at IS NOT NULL;
