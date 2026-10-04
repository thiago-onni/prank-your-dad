-- =====================================================================
-- V004 — terminology: códigos (SIGTAP, CID10, CIAP2, CBO) por competência.
-- Terminologia é GLOBAL (sem tenant / sem RLS).
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS terminology;

CREATE TABLE terminology.code (
  id               bigserial PRIMARY KEY,
  system           text NOT NULL CHECK (system IN ('SIGTAP','CID10','CIAP2','CBO')),
  code             text NOT NULL,
  display          text NOT NULL,
  display_norm     text GENERATED ALWAYS AS (platform.immutable_unaccent(lower(display))) STORED,
  competence_from  text NOT NULL CHECK (competence_from ~ '^[0-9]{6}$'),
  competence_to    text CHECK (competence_to IS NULL OR competence_to ~ '^[0-9]{6}$'),
  attributes       jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (system, code, competence_from)
);
CREATE INDEX code_display_trgm_idx ON terminology.code USING gin (display_norm gin_trgm_ops);
CREATE INDEX code_system_code_idx ON terminology.code (system, code);

GRANT USAGE ON SCHEMA terminology TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA terminology TO sus_nexus_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA terminology TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA terminology GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA terminology GRANT USAGE, SELECT ON SEQUENCES TO sus_nexus_app;
