-- SUS Nexus FHIR Gateway — schema "fhir" (ADR-003: persistência própria JSONB + tabelas de índice)
-- Executado pelo Flyway com usuário administrador. A aplicação conecta com o papel
-- sus_nexus_fhir_app (não superusuário, não proprietário) para que RLS seja efetivo.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE SCHEMA IF NOT EXISTS fhir;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sus_nexus_fhir_app') THEN
    CREATE ROLE sus_nexus_fhir_app LOGIN PASSWORD 'sus_nexus_fhir_app' NOSUPERUSER NOBYPASSRLS;
  END IF;
END
$$;

GRANT USAGE ON SCHEMA fhir TO sus_nexus_fhir_app;

-- ---------------------------------------------------------------------------
-- Recurso corrente (uma linha por recurso lógico, por tenant)
-- ---------------------------------------------------------------------------
CREATE TABLE fhir.fhir_resource (
  id            text        NOT NULL,
  tenant_id     text        NOT NULL,
  resource_type text        NOT NULL,
  version_id    integer     NOT NULL,
  last_updated  timestamptz NOT NULL,
  profile       text[]      NOT NULL DEFAULT '{}',
  content       jsonb       NOT NULL,
  deleted       boolean     NOT NULL DEFAULT false,
  PRIMARY KEY (id),
  CONSTRAINT fhir_resource_type_id_uq UNIQUE (tenant_id, resource_type, id)
);

CREATE INDEX fhir_resource_tenant_type_idx
  ON fhir.fhir_resource (tenant_id, resource_type, id);
CREATE INDEX fhir_resource_last_updated_idx
  ON fhir.fhir_resource (tenant_id, resource_type, last_updated);
CREATE INDEX fhir_resource_profile_idx
  ON fhir.fhir_resource USING gin (profile);

-- ---------------------------------------------------------------------------
-- Histórico (append-only): toda versão gravada, inclusive a corrente
-- ---------------------------------------------------------------------------
CREATE TABLE fhir.fhir_resource_history (
  id            text        NOT NULL,
  tenant_id     text        NOT NULL,
  resource_type text        NOT NULL,
  version_id    integer     NOT NULL,
  last_updated  timestamptz NOT NULL,
  profile       text[]      NOT NULL DEFAULT '{}',
  content       jsonb       NOT NULL,
  deleted       boolean     NOT NULL DEFAULT false,
  PRIMARY KEY (id, version_id)
);

CREATE INDEX fhir_resource_history_tenant_idx
  ON fhir.fhir_resource_history (tenant_id, resource_type, id, version_id DESC);

-- Append-only: proíbe UPDATE/DELETE para o papel da aplicação (apenas INSERT/SELECT abaixo)
CREATE OR REPLACE FUNCTION fhir.reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'fhir_resource_history é append-only';
END
$$;

CREATE TRIGGER fhir_resource_history_append_only
  BEFORE UPDATE OR DELETE ON fhir.fhir_resource_history
  FOR EACH ROW EXECUTE FUNCTION fhir.reject_mutation();

-- ---------------------------------------------------------------------------
-- Tabelas de índice de busca (reconstruídas a cada versão do recurso)
-- ---------------------------------------------------------------------------
CREATE TABLE fhir.fhir_idx_token (
  resource_id text NOT NULL REFERENCES fhir.fhir_resource (id) ON DELETE CASCADE,
  tenant_id   text NOT NULL,
  param       text NOT NULL,
  system      text,
  code        text
);
CREATE INDEX fhir_idx_token_lookup_idx
  ON fhir.fhir_idx_token (tenant_id, param, code, system);
CREATE INDEX fhir_idx_token_resource_idx ON fhir.fhir_idx_token (resource_id);

CREATE TABLE fhir.fhir_idx_string (
  resource_id text NOT NULL REFERENCES fhir.fhir_resource (id) ON DELETE CASCADE,
  tenant_id   text NOT NULL,
  param       text NOT NULL,
  value_norm  text NOT NULL
);
CREATE INDEX fhir_idx_string_prefix_idx
  ON fhir.fhir_idx_string (tenant_id, param, value_norm text_pattern_ops);
CREATE INDEX fhir_idx_string_trgm_idx
  ON fhir.fhir_idx_string USING gin (value_norm gin_trgm_ops);
CREATE INDEX fhir_idx_string_resource_idx ON fhir.fhir_idx_string (resource_id);

CREATE TABLE fhir.fhir_idx_date (
  resource_id text        NOT NULL REFERENCES fhir.fhir_resource (id) ON DELETE CASCADE,
  tenant_id   text        NOT NULL,
  param       text        NOT NULL,
  low         timestamptz NOT NULL,
  high        timestamptz NOT NULL
);
CREATE INDEX fhir_idx_date_range_idx
  ON fhir.fhir_idx_date (tenant_id, param, low, high);
CREATE INDEX fhir_idx_date_resource_idx ON fhir.fhir_idx_date (resource_id);

CREATE TABLE fhir.fhir_idx_reference (
  resource_id text NOT NULL REFERENCES fhir.fhir_resource (id) ON DELETE CASCADE,
  tenant_id   text NOT NULL,
  param       text NOT NULL,
  target_type text,
  target_id   text NOT NULL
);
CREATE INDEX fhir_idx_reference_lookup_idx
  ON fhir.fhir_idx_reference (tenant_id, param, target_id, target_type);
CREATE INDEX fhir_idx_reference_resource_idx ON fhir.fhir_idx_reference (resource_id);

-- ---------------------------------------------------------------------------
-- Row Level Security por tenant (app.tenant_id definido por transação)
-- ---------------------------------------------------------------------------
ALTER TABLE fhir.fhir_resource          ENABLE ROW LEVEL SECURITY;
ALTER TABLE fhir.fhir_resource_history  ENABLE ROW LEVEL SECURITY;
ALTER TABLE fhir.fhir_idx_token         ENABLE ROW LEVEL SECURITY;
ALTER TABLE fhir.fhir_idx_string        ENABLE ROW LEVEL SECURITY;
ALTER TABLE fhir.fhir_idx_date          ENABLE ROW LEVEL SECURITY;
ALTER TABLE fhir.fhir_idx_reference     ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON fhir.fhir_resource
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON fhir.fhir_resource_history
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON fhir.fhir_idx_token
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON fhir.fhir_idx_string
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON fhir.fhir_idx_date
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON fhir.fhir_idx_reference
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- Privilégios mínimos do papel da aplicação
GRANT SELECT, INSERT, UPDATE ON fhir.fhir_resource TO sus_nexus_fhir_app;
GRANT SELECT, INSERT          ON fhir.fhir_resource_history TO sus_nexus_fhir_app;
GRANT SELECT, INSERT, DELETE  ON fhir.fhir_idx_token, fhir.fhir_idx_string,
                                 fhir.fhir_idx_date, fhir.fhir_idx_reference TO sus_nexus_fhir_app;
