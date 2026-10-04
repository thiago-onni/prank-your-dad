-- =====================================================================
-- V014 — exams: pedidos de exame (ciclo pedido → agendamento → realização
--        → laudo → retorno; EXA-001..010), histórico, resultados (SOMENTE
--        metadados + referência segura ao laudo) e vínculo de origem.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS exams;

CREATE TABLE exams.exam_order (
  id                          text PRIMARY KEY,            -- exo_<ULID>
  tenant_id                   text NOT NULL,
  citizen_id                  text NOT NULL,
  status                      text NOT NULL CHECK (status IN
                                ('requested','authorized','scheduled','collected','performed','reported','cancelled','not_performed')),
  requested_at                timestamptz NOT NULL,
  exam_code                   text NOT NULL,
  code_system                 text NOT NULL DEFAULT 'SIGTAP' CHECK (code_system IN ('SIGTAP','LOINC','LOCAL')),
  exam_description            text,
  category                    text CHECK (category IS NULL OR category IN ('laboratory','imaging','other')),
  priority                    text CHECK (priority IS NULL OR priority IN ('routine','priority','urgent')),
  requesting_cnes             text CHECK (requesting_cnes IS NULL OR requesting_cnes ~ '^[0-9]{7}$'),
  requesting_professional_id  text,
  performer_cnes              text CHECK (performer_cnes IS NULL OR performer_cnes ~ '^[0-9]{7}$'),
  regulation_request_id       text,
  appointment_id              text,
  scheduled_at                timestamptz,
  performed_at                timestamptz,
  reported_at                 timestamptz,
  care_line                   text,
  issues                      text[] NOT NULL DEFAULT '{}',
  source_system               text NOT NULL,
  source_record_id            text NOT NULL,
  source_record_version       text,
  created_at                  timestamptz NOT NULL DEFAULT now(),
  updated_at                  timestamptz NOT NULL DEFAULT now(),
  version                     bigint NOT NULL DEFAULT 0
);
CREATE INDEX exam_order_citizen_idx ON exams.exam_order (tenant_id, citizen_id, requested_at DESC);
CREATE INDEX exam_order_status_idx ON exams.exam_order (tenant_id, status, requested_at);
CREATE INDEX exam_order_requesting_idx ON exams.exam_order (tenant_id, requesting_cnes, status);
CREATE INDEX exam_order_issues_idx ON exams.exam_order USING gin (issues);

CREATE TABLE exams.exam_order_status_history (
  id              text PRIMARY KEY,                        -- esh_<ULID>
  tenant_id       text NOT NULL,
  order_id        text NOT NULL REFERENCES exams.exam_order(id),
  status          text NOT NULL,
  previous_status text,
  reason          text,
  occurred_at     timestamptz NOT NULL,
  recorded_at     timestamptz NOT NULL DEFAULT now(),
  source_system   text,
  actor_id        text
);
CREATE INDEX exam_order_history_idx ON exams.exam_order_status_history (order_id, occurred_at);
CREATE TRIGGER exam_order_status_history_append_only
  BEFORE UPDATE OR DELETE ON exams.exam_order_status_history
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

-- Resultado/laudo: metadados e referência segura; o conteúdo NUNCA é persistido aqui (EXA-006)
CREATE TABLE exams.exam_result (
  id                     text PRIMARY KEY,                 -- exr_<ULID>
  tenant_id              text NOT NULL,
  order_id               text NOT NULL REFERENCES exams.exam_order(id),
  reported_at            timestamptz NOT NULL,
  status                 text NOT NULL CHECK (status IN ('final','preliminary','amended','inconclusive','cancelled')),
  critical               boolean NOT NULL DEFAULT false,
  performer_cnes         text CHECK (performer_cnes IS NULL OR performer_cnes ~ '^[0-9]{7}$'),
  document_ref           text,
  document_content_type  text,
  document_sha256        text,
  observations           jsonb NOT NULL DEFAULT '[]'::jsonb,   -- somente code/code_system/value/unit/abnormal (sem texto livre)
  observations_count     int NOT NULL DEFAULT 0,
  followup_task_id       text,
  source_system          text NOT NULL,
  source_record_id       text,
  created_at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX exam_result_order_idx ON exams.exam_result (order_id, reported_at);

CREATE TABLE exams.exam_order_source_link (
  id                     text PRIMARY KEY,                 -- link_<ULID>
  tenant_id              text NOT NULL,
  order_id               text NOT NULL REFERENCES exams.exam_order(id),
  source_system          text NOT NULL,
  connector              text,
  source_record_id       text NOT NULL,
  source_record_version  text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_system, source_record_id)
);
CREATE INDEX exam_order_source_link_order_idx ON exams.exam_order_source_link (order_id);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['exam_order','exam_order_status_history','exam_result','exam_order_source_link'] LOOP
    EXECUTE format('ALTER TABLE exams.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE exams.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON exams.%I USING (tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA exams TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA exams TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA exams GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
