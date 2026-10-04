-- =====================================================================
-- V001 — platform: papel da aplicação, extensões, schema platform,
--        funções utilitárias, event_outbox e event_inbox.
-- Executado pelo usuário administrador (quarkus.flyway.username).
-- =====================================================================

-- Papel da aplicação: NÃO é superusuário nem dono das tabelas, portanto
-- as políticas de RLS se aplicam a ele (fail-closed sem app.tenant_id).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sus_nexus_app') THEN
    CREATE ROLE sus_nexus_app LOGIN PASSWORD 'sus_nexus_app';
  END IF;
END
$$;

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE SCHEMA IF NOT EXISTS platform;

-- unaccent() não é IMMUTABLE; este wrapper permite uso em índices e colunas geradas.
CREATE OR REPLACE FUNCTION platform.immutable_unaccent(text)
  RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$ SELECT public.unaccent('public.unaccent', $1) $$;

-- Função usada pelas políticas de RLS. Retorna NULL quando não há tenant
-- na transação, o que faz toda política avaliar como falsa (fail-closed).
CREATE OR REPLACE FUNCTION platform.current_tenant()
  RETURNS text
  LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '') $$;

-- Gatilho genérico que impede UPDATE/DELETE (tabelas append-only)
CREATE OR REPLACE FUNCTION platform.deny_mutation()
  RETURNS trigger
  LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'tabela % é append-only (operação % negada)', TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'integrity_constraint_violation';
END
$$;

-- ---------------------------------------------------------------------
-- Outbox transacional (Debezium Outbox Event Router)
-- payload = envelope completo (contracts/events/envelope.schema.json)
-- ---------------------------------------------------------------------
CREATE TABLE platform.event_outbox (
  id              text PRIMARY KEY,                 -- evt_<ULID> (= event_id do envelope)
  tenant_id       text NOT NULL,
  aggregate_type  text NOT NULL,
  aggregate_id    text NOT NULL,
  event_type      text NOT NULL,
  payload         jsonb NOT NULL,
  headers         jsonb NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX event_outbox_created_idx ON platform.event_outbox (created_at);
CREATE INDEX event_outbox_aggregate_idx ON platform.event_outbox (aggregate_type, aggregate_id);

-- ---------------------------------------------------------------------
-- Inbox de idempotência de consumidores
-- ---------------------------------------------------------------------
CREATE TABLE platform.event_inbox (
  event_id        text NOT NULL,
  consumer_group  text NOT NULL,
  processed_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, consumer_group)
);

-- ---------------------------------------------------------------------
-- Idempotência de API (Idempotency-Key, 72 h)
-- ---------------------------------------------------------------------
CREATE TABLE platform.idempotency_key (
  tenant_id       text NOT NULL,
  idempotency_key text NOT NULL,
  request_hash    text NOT NULL,
  response_status int  NOT NULL,
  response_body   jsonb,
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, idempotency_key)
);
ALTER TABLE platform.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform.idempotency_key
  USING (tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------
-- Permissões
-- ---------------------------------------------------------------------
GRANT USAGE ON SCHEMA platform TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform TO sus_nexus_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA platform TO sus_nexus_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA platform TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT USAGE, SELECT ON SEQUENCES TO sus_nexus_app;
