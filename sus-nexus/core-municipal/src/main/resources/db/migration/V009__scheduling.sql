-- =====================================================================
-- V009 — scheduling: agenda consolidada da rede (agendamento, histórico de
--        status, vínculo de origem e duplicidades AGE-004).
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS scheduling;

CREATE TABLE scheduling.appointment (
  id                     text PRIMARY KEY,                 -- apt_<ULID>
  tenant_id              text NOT NULL,
  citizen_id             text NOT NULL,
  status                 text NOT NULL CHECK (status IN
                           ('proposed','booked','confirmed','arrived','fulfilled','cancelled','noshow','waitlist')),
  kind                   text NOT NULL CHECK (kind IN ('direct','regulated','walk_in','block','waitlist','return')),
  service_code           text,
  code_system            text CHECK (code_system IS NULL OR code_system IN ('SIGTAP','LOCAL')),
  health_unit_cnes       text CHECK (health_unit_cnes IS NULL OR health_unit_cnes ~ '^[0-9]{7}$'),
  professional_id        text,
  scheduled_start        timestamptz NOT NULL,
  scheduled_end          timestamptz,
  regulation_request_id  text,
  exam_order_id          text,
  care_line              text,
  cancellation_reason    text,
  source_system          text NOT NULL,
  source_record_id       text NOT NULL,
  source_record_version  text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  version                bigint NOT NULL DEFAULT 0
);
CREATE INDEX appointment_citizen_idx ON scheduling.appointment (tenant_id, citizen_id, scheduled_start DESC);
CREATE INDEX appointment_cnes_idx ON scheduling.appointment (tenant_id, health_unit_cnes, scheduled_start DESC);
CREATE INDEX appointment_status_idx ON scheduling.appointment (tenant_id, status, scheduled_start);
CREATE INDEX appointment_dup_idx ON scheduling.appointment (tenant_id, citizen_id, service_code, scheduled_start);

CREATE TABLE scheduling.appointment_status_history (
  id              text PRIMARY KEY,                        -- ash_<ULID>
  tenant_id       text NOT NULL,
  appointment_id  text NOT NULL REFERENCES scheduling.appointment(id),
  status          text NOT NULL,
  previous_status text,
  reason          text,
  occurred_at     timestamptz NOT NULL,
  recorded_at     timestamptz NOT NULL DEFAULT now(),
  source_system   text,
  actor_id        text
);
CREATE INDEX appointment_history_idx ON scheduling.appointment_status_history (appointment_id, occurred_at);
CREATE TRIGGER appointment_status_history_append_only
  BEFORE UPDATE OR DELETE ON scheduling.appointment_status_history
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

CREATE TABLE scheduling.appointment_source_link (
  id                     text PRIMARY KEY,                 -- link_<ULID>
  tenant_id              text NOT NULL,
  appointment_id         text NOT NULL REFERENCES scheduling.appointment(id),
  source_system          text NOT NULL,
  connector              text,
  source_record_id       text NOT NULL,
  source_record_version  text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_system, source_record_id)
);
CREATE INDEX appointment_source_link_apt_idx ON scheduling.appointment_source_link (appointment_id);

CREATE TABLE scheduling.appointment_duplicate (
  id               text PRIMARY KEY,                       -- dup_<ULID>
  tenant_id        text NOT NULL,
  citizen_id       text NOT NULL,
  service_code     text,
  appointment_ids  jsonb NOT NULL,                         -- ["apt_...", "apt_..."]
  window_hours     int NOT NULL,
  detected_at      timestamptz NOT NULL DEFAULT now(),
  resolved_at      timestamptz,
  resolution       text
);
CREATE INDEX appointment_duplicate_idx ON scheduling.appointment_duplicate (tenant_id, detected_at DESC);
CREATE INDEX appointment_duplicate_citizen_idx ON scheduling.appointment_duplicate (tenant_id, citizen_id);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['appointment','appointment_status_history','appointment_source_link','appointment_duplicate'] LOOP
    EXECUTE format('ALTER TABLE scheduling.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE scheduling.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON scheduling.%I USING (tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA scheduling TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA scheduling TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA scheduling GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
