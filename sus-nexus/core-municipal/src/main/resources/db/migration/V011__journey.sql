-- =====================================================================
-- V011 — journey: read model da linha do tempo do cidadão (JOR-*).
--        Alimentado por consumidores Kafka; merge reatribui citizen_id
--        (original_citizen_id guarda a origem para unmerge).
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS journey;

CREATE TABLE journey.timeline_event (
  id                   text PRIMARY KEY,                   -- tle_<ULID>
  tenant_id            text NOT NULL,
  citizen_id           text NOT NULL,
  original_citizen_id  text NOT NULL,
  domain               text NOT NULL CHECK (domain IN
                         ('identity','aps','schedule','regulation','exam','hospital','careplan','task','production','communication')),
  event_type           text NOT NULL,
  event_id             text NOT NULL,                      -- evt_ de origem (idempotência da projeção)
  aggregate_id         text,
  occurred_at          timestamptz NOT NULL,
  recorded_at          timestamptz NOT NULL DEFAULT now(),
  source_system        text NOT NULL,
  cnes                 text,
  health_unit_name     text,
  professional_ref     text,
  status               text NOT NULL,
  confidence           text NOT NULL DEFAULT 'confirmed'
                         CHECK (confidence IN ('confirmed','pending','divergent','unsynced')),
  sensitivity          text NOT NULL DEFAULT 'internal'
                         CHECK (sensitivity IN ('public','internal','restricted','highly_restricted')),
  summary              text,
  detail_ref           text,
  care_line            text,
  correlation_chain    text[] NOT NULL DEFAULT '{}',
  UNIQUE (tenant_id, event_id)
);
CREATE INDEX timeline_citizen_idx ON journey.timeline_event (tenant_id, citizen_id, occurred_at DESC, id DESC);
CREATE INDEX timeline_original_idx ON journey.timeline_event (tenant_id, original_citizen_id);
CREATE INDEX timeline_aggregate_idx ON journey.timeline_event (tenant_id, aggregate_id);

ALTER TABLE journey.timeline_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE journey.timeline_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON journey.timeline_event
  USING (tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

GRANT USAGE ON SCHEMA journey TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA journey TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA journey GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
