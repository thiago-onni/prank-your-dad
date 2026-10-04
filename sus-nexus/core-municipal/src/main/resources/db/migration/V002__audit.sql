-- =====================================================================
-- V002 — audit: audit_log (append-only, encadeado por hash) e access_log
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_log (
  id             text PRIMARY KEY,                 -- aud_<ULID>
  tenant_id      text NOT NULL,
  seq            bigserial NOT NULL,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  actor_id       text NOT NULL,
  actor_roles    text[] NOT NULL DEFAULT '{}',
  action         text NOT NULL,
  resource_type  text NOT NULL,
  resource_id    text,
  citizen_id     text,
  reason         text,
  details        jsonb NOT NULL DEFAULT '{}'::jsonb,
  correlation_id text,
  prev_hash      text NOT NULL,                    -- hash do registro anterior do tenant ('GENESIS' no primeiro)
  hash           text NOT NULL                     -- SHA-256(prev_hash || conteúdo canônico)
);
CREATE UNIQUE INDEX audit_log_tenant_seq_idx ON audit.audit_log (tenant_id, seq);
CREATE INDEX audit_log_resource_idx ON audit.audit_log (tenant_id, resource_type, resource_id);
CREATE INDEX audit_log_citizen_idx ON audit.audit_log (tenant_id, citizen_id);

ALTER TABLE audit.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.audit_log
  USING (tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

CREATE TRIGGER audit_log_append_only
  BEFORE UPDATE OR DELETE ON audit.audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

CREATE TABLE audit.access_log (
  id             text PRIMARY KEY,                 -- acc_<ULID>
  tenant_id      text NOT NULL,
  actor_id       text NOT NULL,
  actor_roles    text[] NOT NULL DEFAULT '{}',
  action         text NOT NULL,
  resource_type  text NOT NULL,
  resource_id    text,
  citizen_id     text,
  purpose        text,
  decision       text NOT NULL CHECK (decision IN ('allow','deny')),
  break_glass    boolean NOT NULL DEFAULT false,
  justification  text,
  correlation_id text,
  occurred_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX access_log_citizen_idx ON audit.access_log (tenant_id, citizen_id, occurred_at DESC);
CREATE INDEX access_log_actor_idx ON audit.access_log (tenant_id, actor_id, occurred_at DESC);
CREATE INDEX access_log_occurred_idx ON audit.access_log (tenant_id, occurred_at DESC);

ALTER TABLE audit.access_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.access_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.access_log
  USING (tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

CREATE TRIGGER access_log_append_only
  BEFORE UPDATE OR DELETE ON audit.access_log
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

GRANT USAGE ON SCHEMA audit TO sus_nexus_app;
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA audit TO sus_nexus_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA audit TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT SELECT, INSERT ON TABLES TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT USAGE, SELECT ON SEQUENCES TO sus_nexus_app;
