-- =====================================================================
-- V013 — regulation: solicitações regulatórias espelhadas do sistema
--        oficial (REG-001/002), histórico, decisões (somente registro),
--        pendências (REG-005), vínculo de origem, oferta/capacidade (REG-006)
--        e políticas de SLA por prioridade (REG-010).
--        O barramento NUNCA decide: decisão/prioridade vêm do sistema oficial.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS regulation;

CREATE TABLE regulation.regulation_request (
  id                          text PRIMARY KEY,            -- reg_<ULID>
  tenant_id                   text NOT NULL,
  citizen_id                  text NOT NULL,
  kind                        text NOT NULL CHECK (kind IN ('consultation','exam','procedure','surgery','admission')),
  status                      text NOT NULL CHECK (status IN
                                ('requested','pending_documents','returned','under_review','authorized','denied',
                                 'scheduled','cancelled','no_show','performed','expired')),
  priority                    text NOT NULL DEFAULT 'elective'
                                CHECK (priority IN ('elective','priority','urgent','emergency')),
  requested_at                timestamptz NOT NULL,
  requested_service_code      text NOT NULL,
  code_system                 text NOT NULL DEFAULT 'SIGTAP' CHECK (code_system IN ('SIGTAP','LOCAL')),
  specialty                   text,
  requesting_cnes             text CHECK (requesting_cnes IS NULL OR requesting_cnes ~ '^[0-9]{7}$'),
  requesting_professional_id  text,
  requesting_professional_cbo text,
  justification_present       boolean,
  attached_documents_count    int CHECK (attached_documents_count IS NULL OR attached_documents_count >= 0),
  provider_cnes               text CHECK (provider_cnes IS NULL OR provider_cnes ~ '^[0-9]{7}$'),
  scheduled_at                timestamptz,
  appointment_id              text,
  regulator_id                text,
  decision_reason             text,
  sla_due_at                  timestamptz,
  sla_policy_id               text,
  sla_breached                boolean NOT NULL DEFAULT false,
  decided_at                  timestamptz,
  source_system               text NOT NULL,
  source_record_id            text NOT NULL,
  source_record_version       text,
  created_at                  timestamptz NOT NULL DEFAULT now(),
  updated_at                  timestamptz NOT NULL DEFAULT now(),
  version                     bigint NOT NULL DEFAULT 0
);
CREATE INDEX regulation_request_citizen_idx ON regulation.regulation_request (tenant_id, citizen_id, requested_at DESC);
CREATE INDEX regulation_request_queue_idx ON regulation.regulation_request (tenant_id, status, priority, requested_at);
CREATE INDEX regulation_request_service_idx ON regulation.regulation_request (tenant_id, requested_service_code, status);
CREATE INDEX regulation_request_specialty_idx ON regulation.regulation_request (tenant_id, specialty, status);
CREATE INDEX regulation_request_requesting_idx ON regulation.regulation_request (tenant_id, requesting_cnes, status);
CREATE INDEX regulation_request_provider_idx ON regulation.regulation_request (tenant_id, provider_cnes, status);
CREATE INDEX regulation_request_sla_idx ON regulation.regulation_request (tenant_id, sla_due_at) WHERE sla_breached = false;

CREATE TABLE regulation.regulation_status_history (
  id              text PRIMARY KEY,                        -- rsh_<ULID>
  tenant_id       text NOT NULL,
  request_id      text NOT NULL REFERENCES regulation.regulation_request(id),
  status          text NOT NULL,
  previous_status text,
  reason          text,
  actor_kind      text NOT NULL CHECK (actor_kind IN ('regulator','requester','provider','system','workflow','connector')),
  actor_id        text,
  occurred_at     timestamptz NOT NULL,
  recorded_at     timestamptz NOT NULL DEFAULT now(),
  source_system   text
);
CREATE INDEX regulation_history_idx ON regulation.regulation_status_history (request_id, occurred_at);
CREATE TRIGGER regulation_status_history_append_only
  BEFORE UPDATE OR DELETE ON regulation.regulation_status_history
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

-- Decisão registrada a partir do sistema oficial (REG-003): o barramento só espelha.
CREATE TABLE regulation.regulation_decision (
  id            text PRIMARY KEY,                          -- rdec_<ULID>
  tenant_id     text NOT NULL,
  request_id    text NOT NULL REFERENCES regulation.regulation_request(id),
  regulator_id  text,
  decision      text NOT NULL CHECK (decision IN ('authorized','denied','returned')),
  reason        text,
  occurred_at   timestamptz NOT NULL,
  recorded_at   timestamptz NOT NULL DEFAULT now(),
  source_system text
);
CREATE INDEX regulation_decision_idx ON regulation.regulation_decision (request_id, occurred_at);
CREATE TRIGGER regulation_decision_append_only
  BEFORE UPDATE OR DELETE ON regulation.regulation_decision
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

CREATE TABLE regulation.regulation_issue (
  id             text PRIMARY KEY,                         -- ris_<ULID>
  tenant_id      text NOT NULL,
  request_id     text NOT NULL REFERENCES regulation.regulation_request(id),
  kind           text NOT NULL CHECK (kind IN
                   ('missing_document','missing_field','clinical_justification','duplicate','other',
                    'sla_breached','no_capacity','expired')),
  status         text NOT NULL CHECK (status IN ('open','resolved')),
  description    text,
  origin_kind    text CHECK (origin_kind IS NULL OR origin_kind IN ('user','agent','rule','workflow','connector')),
  origin_id      text,
  origin_version text,
  created_by     text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  resolved_at    timestamptz,
  resolution     text
);
CREATE INDEX regulation_issue_request_idx ON regulation.regulation_issue (request_id, status);
CREATE INDEX regulation_issue_kind_idx ON regulation.regulation_issue (tenant_id, kind, status);

CREATE TABLE regulation.regulation_source_link (
  id                     text PRIMARY KEY,                 -- link_<ULID>
  tenant_id              text NOT NULL,
  request_id             text NOT NULL REFERENCES regulation.regulation_request(id),
  source_system          text NOT NULL,
  connector              text,
  source_record_id       text NOT NULL,
  source_record_version  text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_system, source_record_id)
);
CREATE INDEX regulation_source_link_req_idx ON regulation.regulation_source_link (request_id);

-- Oferta/capacidade por prestador, serviço e competência (REG-006)
CREATE TABLE regulation.provider_capacity (
  id             text PRIMARY KEY,                         -- cap_<ULID>
  tenant_id      text NOT NULL,
  provider_cnes  text NOT NULL CHECK (provider_cnes ~ '^[0-9]{7}$'),
  service_code   text NOT NULL,
  code_system    text NOT NULL DEFAULT 'SIGTAP',
  competence     text NOT NULL CHECK (competence ~ '^[0-9]{6}$'),
  offered        int NOT NULL CHECK (offered >= 0),
  used           int NOT NULL DEFAULT 0 CHECK (used >= 0),
  available      int NOT NULL,
  source_system  text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, provider_cnes, service_code, competence)
);
CREATE INDEX provider_capacity_service_idx ON regulation.provider_capacity (tenant_id, service_code, competence);

-- SLA de decisão por prioridade (REG-010): linhas globais (tenant_id NULL) + sobrescrita municipal
CREATE TABLE regulation.regulation_sla_policy (
  id              text PRIMARY KEY,
  tenant_id       text,
  priority        text NOT NULL CHECK (priority IN ('elective','priority','urgent','emergency')),
  due_in          interval NOT NULL,
  policy_version  text NOT NULL DEFAULT '1.0',
  effective_from  timestamptz NOT NULL DEFAULT now(),
  effective_to    timestamptz,
  active          boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX regulation_sla_policy_idx ON regulation.regulation_sla_policy (priority, active);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['regulation_request','regulation_status_history','regulation_decision',
                           'regulation_issue','regulation_source_link','provider_capacity'] LOOP
    EXECUTE format('ALTER TABLE regulation.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE regulation.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON regulation.%I USING (tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

ALTER TABLE regulation.regulation_sla_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE regulation.regulation_sla_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_or_global ON regulation.regulation_sla_policy
  USING (tenant_id IS NULL OR tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

INSERT INTO regulation.regulation_sla_policy (id, priority, due_in) VALUES
  ('rsla_elective',  'elective',  interval '90 days'),
  ('rsla_priority',  'priority',  interval '30 days'),
  ('rsla_urgent',    'urgent',    interval '7 days'),
  ('rsla_emergency', 'emergency', interval '1 day');

GRANT USAGE ON SCHEMA regulation TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA regulation TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA regulation GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
