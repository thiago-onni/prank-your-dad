-- =====================================================================
-- V008 — integration: registry de conectores, ledger espelho de mensagens
--        (SEM payload), erros, dead letters e reconciliação.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE integration.connector (
  connector_id       text NOT NULL,
  tenant_id          text NOT NULL,
  connector_version  text NOT NULL,
  source_system      text NOT NULL,
  health             text NOT NULL DEFAULT 'unknown'
                       CHECK (health IN ('healthy','degraded','down','unknown')),
  health_detail      text,
  last_message_at    timestamptz,
  last_heartbeat_at  timestamptz,
  descriptor         jsonb NOT NULL DEFAULT '{}'::jsonb,
  metrics            jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  version            bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, connector_id)
);

CREATE TABLE integration.message (
  id                     text PRIMARY KEY,                 -- msg_<ULID> (gerado pelo conector)
  tenant_id              text NOT NULL,
  connector_id           text NOT NULL,
  source_system          text NOT NULL,
  source_record_id       text,
  source_record_version  text,
  entity_type            text,
  status                 text NOT NULL CHECK (status IN
                           ('received','transformed','validated','published','processed',
                            'failed','dead_lettered','reprocessing')),
  raw_ref                text,                             -- referência à raw zone (nunca o payload)
  raw_sha256             text,
  correlation_id         text,
  received_at            timestamptz NOT NULL,
  processed_at           timestamptz,
  attempts               int NOT NULL DEFAULT 0,
  last_error             jsonb,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  version                bigint NOT NULL DEFAULT 0
);
CREATE INDEX message_connector_received_idx ON integration.message (tenant_id, connector_id, received_at DESC);
CREATE INDEX message_status_idx ON integration.message (tenant_id, status, received_at DESC);
CREATE INDEX message_source_idx ON integration.message (tenant_id, source_system, source_record_id);

CREATE TABLE integration.error (
  id            text PRIMARY KEY,                          -- ierr_<ULID>
  tenant_id     text NOT NULL,
  message_id    text NOT NULL REFERENCES integration.message(id),
  connector_id  text NOT NULL,
  stage         text,
  code          text,
  message       text,
  attempt       int NOT NULL DEFAULT 1,
  occurred_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX error_message_idx ON integration.error (tenant_id, message_id, occurred_at DESC);
CREATE INDEX error_connector_idx ON integration.error (tenant_id, connector_id, occurred_at DESC);

CREATE TABLE integration.dead_letter (
  id            text PRIMARY KEY,                          -- dlq_<ULID>
  tenant_id     text NOT NULL,
  message_id    text NOT NULL,
  connector_id  text NOT NULL,
  topic         text,
  reason        text NOT NULL,
  stage         text,
  attempts      int NOT NULL DEFAULT 0,
  owner         text,
  payload_ref   text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  triaged_at    timestamptz,
  triaged_by    text
);
CREATE INDEX dead_letter_open_idx ON integration.dead_letter (tenant_id, connector_id) WHERE triaged_at IS NULL;
CREATE INDEX dead_letter_created_idx ON integration.dead_letter (tenant_id, created_at DESC);

CREATE TABLE integration.reconciliation (
  id            text PRIMARY KEY,                          -- rec_<ULID>
  tenant_id     text NOT NULL,
  connector_id  text NOT NULL,
  entity_type   text NOT NULL,
  period_start  timestamptz NOT NULL,
  period_end    timestamptz NOT NULL,
  source_count  int NOT NULL,
  bus_count     int NOT NULL,
  gap           int NOT NULL,
  checked_at    timestamptz NOT NULL DEFAULT now(),
  details       jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX reconciliation_connector_idx ON integration.reconciliation (tenant_id, connector_id, checked_at DESC);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['connector','message','error','dead_letter','reconciliation'] LOOP
    EXECUTE format('ALTER TABLE integration.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE integration.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON integration.%I USING (tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA integration TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA integration TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA integration GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
