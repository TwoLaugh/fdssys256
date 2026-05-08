-- AI module — 01d prompt template storage.
-- See lld/ai.md §V…__ai_create_prompt_template.
--
-- Append-only-with-versioning: PromptTemplateLoader scans lld/prompts/*.md at
-- startup, computes sha256 of each file's bytes, and INSERTs a new (name, version)
-- row whenever the hash changes; existing rows are never updated. Production
-- callers resolve through PromptTemplateService which prefers the highest
-- version for a given name.

CREATE TABLE ai_prompt_template (
    id                    uuid        PRIMARY KEY,
    name                  varchar(128) NOT NULL,
    version               integer      NOT NULL,
    model_tier            varchar(16)  NOT NULL,        -- CHEAP | MID | HIGH
    system_prompt         text         NOT NULL,
    user_prompt_template  text         NOT NULL,
    output_schema         jsonb,                        -- nullable; free-text outputs leave it null
    tools                 jsonb,                        -- nullable; tool-use definitions
    notes                 text,
    source_file           varchar(255) NOT NULL,        -- e.g. lld/prompts/01-taste-profile-delta.md
    source_hash           char(64)     NOT NULL,        -- sha256 hex
    created_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_prompt_template_name_version UNIQUE (name, version)
);

-- Hot read: PromptTemplateService.getLatest scans by name newest-version-first.
CREATE INDEX idx_ai_prompt_template_name_version_desc
    ON ai_prompt_template (name, version DESC);
