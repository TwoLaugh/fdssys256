-- pgvector extension required by recipe and preference modules for embedding columns.
-- Installed here in core's first migration so all subsequent migrations can use vector(N).
CREATE EXTENSION IF NOT EXISTS vector;
