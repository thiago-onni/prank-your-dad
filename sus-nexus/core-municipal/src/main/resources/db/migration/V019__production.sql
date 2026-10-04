-- =====================================================================
-- V019 — production: produção ambulatorial/hospitalar (BPA-C, BPA-I, APAC,
--        AIH) com pré-auditoria por regras versionadas (PRO-001..010,
--        Workflow 3). Registro por vínculo de origem, histórico append-only,
--        pendências com rule_version, lotes (somente registros validados e
--        aprovação humana obrigatória), exportação em layout de referência,
--        retornos do processamento oficial e prazos por competência.
--        CNS/CPF: somente hash (HMAC por tenant) + máscara + cifra (export);
--        nunca em claro. Dado administrativo: NÃO é projetado na timeline.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS production;

CREATE TABLE production.production_record (
  id                                 text PRIMARY KEY,         -- prod_<ULID>
  tenant_id                          text NOT NULL,
  kind                               text NOT NULL CHECK (kind IN ('bpa_c','bpa_i','apac','aih')),
  competence                         text NOT NULL CHECK (competence ~ '^[0-9]{4}(0[1-9]|1[0-2])$'),
  cnes                               text NOT NULL CHECK (cnes ~ '^[0-9]{7}$'),
  professional_cns_hash              text,
  professional_cns_masked            text,
  professional_cns_enc               bytea,
  professional_cbo                   text NOT NULL CHECK (professional_cbo ~ '^[0-9]{6}$'),
  procedure_code                     text NOT NULL CHECK (procedure_code ~ '^[0-9]{10}$'),
  quantity                           int NOT NULL CHECK (quantity > 0),
  citizen_id                         text,
  citizen_identifier_system          text CHECK (citizen_identifier_system IS NULL OR citizen_identifier_system IN ('CNS','CPF')),
  citizen_identifier_hash            text,
  citizen_identifier_masked          text,
  citizen_identifier_enc             bytea,
  cid_code                           text,
  attendance_date                    date NOT NULL,
  character_of_care                  text CHECK (character_of_care IS NULL OR character_of_care IN ('elective','urgency','work_accident','other')),
  apac_number                        text,
  aih_number                         text,
  encounter_source_system            text,
  encounter_source_record_id         text,
  appointment_source_system          text,
  appointment_source_record_id       text,
  appointment_id                     text,
  hospital_episode_source_system     text,
  hospital_episode_source_record_id  text,
  hospital_episode_id                text,
  status                             text NOT NULL CHECK (status IN ('generated','validated','pending','exported','transmitted','received','rejected','corrected','approved','paid')),
  rule_version                       text,
  validated_at                       timestamptz,
  facts                              jsonb NOT NULL DEFAULT '{}'::jsonb,   -- fatos usados pelas regras (reprodutibilidade; sem PII)
  unit_value                         numeric(12,2),
  estimated_value                    numeric(14,2),
  paid_amount                        numeric(14,2),
  approved_quantity                  int,
  outcome_reason_code                text,
  outcome_reason                     text,
  batch_id                           text,
  exported_at                        timestamptz,
  deadline_at                        timestamptz,
  correction_count                   int NOT NULL DEFAULT 0,
  last_corrected_at                  timestamptz,
  payload_hash                       text,                    -- detecta reenvio idêntico da origem
  source_system                      text NOT NULL,
  source_record_id                   text NOT NULL,
  source_record_version              text,
  created_at                         timestamptz NOT NULL DEFAULT now(),
  updated_at                         timestamptz NOT NULL DEFAULT now(),
  version                            bigint NOT NULL DEFAULT 0
);
CREATE INDEX production_record_competence_idx ON production.production_record (tenant_id, competence, cnes, kind, status);
CREATE INDEX production_record_duplicate_idx ON production.production_record (tenant_id, citizen_id, procedure_code, attendance_date, cnes);
CREATE INDEX production_record_batch_idx ON production.production_record (batch_id);

-- Histórico (append-only): mudanças de status, correções com justificativa e retornos oficiais
CREATE TABLE production.production_record_history (
  id              text PRIMARY KEY,                           -- prh_<ULID>
  tenant_id       text NOT NULL,
  record_id       text NOT NULL REFERENCES production.production_record(id),
  action          text NOT NULL,                              -- created|updated|validated|pending|corrected|batched|exported|outcome:<x>|deadline_missed
  from_status     text,
  to_status       text,
  changes         jsonb NOT NULL DEFAULT '{}'::jsonb,         -- campo → {from,to}; identificadores só "changed"
  justification   text,
  rule_version    text,
  actor_id        text,
  actor_kind      text,
  occurred_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX production_record_history_idx ON production.production_record_history (record_id, occurred_at);
CREATE TRIGGER production_record_history_append_only
  BEFORE UPDATE OR DELETE ON production.production_record_history
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

CREATE TABLE production.production_validation_issue (
  id               text PRIMARY KEY,                          -- pis_<ULID>
  tenant_id        text NOT NULL,
  record_id        text NOT NULL REFERENCES production.production_record(id),
  rule_id          text NOT NULL,
  rule_version     text NOT NULL,
  severity         text NOT NULL CHECK (severity IN ('error','warning')),
  field            text,
  message          text NOT NULL,
  status           text NOT NULL CHECK (status IN ('open','resolved','waived')),
  origin           text NOT NULL DEFAULT 'rule' CHECK (origin IN ('rule','workflow','official_return')),
  task_id          text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  resolved_at      timestamptz,
  resolved_by      text,
  resolution_note  text
);
CREATE INDEX production_issue_record_idx ON production.production_validation_issue (record_id, status);
CREATE INDEX production_issue_queue_idx ON production.production_validation_issue (tenant_id, status, severity, rule_id);
CREATE UNIQUE INDEX production_issue_one_open_idx
  ON production.production_validation_issue (record_id, rule_id) WHERE status = 'open';

CREATE TABLE production.production_batch (
  id                       text PRIMARY KEY,                  -- pbat_<ULID>
  tenant_id                text NOT NULL,
  competence               text NOT NULL CHECK (competence ~ '^[0-9]{4}(0[1-9]|1[0-2])$'),
  cnes                     text NOT NULL CHECK (cnes ~ '^[0-9]{7}$'),
  kind                     text NOT NULL CHECK (kind IN ('bpa_c','bpa_i','apac','aih')),
  status                   text NOT NULL CHECK (status IN ('draft','approved','exported','transmitted','processed')),
  records_count            int NOT NULL DEFAULT 0,
  total_quantity           int NOT NULL DEFAULT 0,
  estimated_value          numeric(14,2) NOT NULL DEFAULT 0,
  created_by               text,
  approved_by              text,
  approved_at              timestamptz,
  approval_justification   text,
  export_layout            text,
  export_file_ref          text,
  export_sha256            text,
  export_size_bytes        bigint,
  export_lines             int,
  export_missing_ids       int,
  exported_by              text,
  exported_at              timestamptz,
  protocol_number          text,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  version                  bigint NOT NULL DEFAULT 0
);
CREATE INDEX production_batch_idx ON production.production_batch (tenant_id, competence, cnes, status);

CREATE TABLE production.production_batch_item (
  id           text PRIMARY KEY,                              -- pbi_<ULID>
  tenant_id    text NOT NULL,
  batch_id     text NOT NULL REFERENCES production.production_batch(id),
  record_id    text NOT NULL REFERENCES production.production_record(id),
  line_number  int NOT NULL,
  removed_at   timestamptz,                                   -- correção de registro em lote rascunho
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (batch_id, record_id)
);

-- Exportações e transmissões (confirmadas pelo sistema oficial) por lote
CREATE TABLE production.production_submission (
  id               text PRIMARY KEY,                          -- psub_<ULID>
  tenant_id        text NOT NULL,
  batch_id         text NOT NULL REFERENCES production.production_batch(id),
  kind             text NOT NULL CHECK (kind IN ('export','transmission')),
  layout           text,
  file_ref         text,
  sha256           text,
  size_bytes       bigint,
  lines            int,
  protocol_number  text,
  actor_id         text,
  occurred_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX production_submission_batch_idx ON production.production_submission (batch_id, occurred_at);

-- Retornos do processamento oficial (SIA/SIH) por registro ou lote
CREATE TABLE production.production_outcome (
  id                 text PRIMARY KEY,                        -- pout_<ULID>
  tenant_id          text NOT NULL,
  record_id          text REFERENCES production.production_record(id),
  batch_id           text REFERENCES production.production_batch(id),
  outcome            text NOT NULL CHECK (outcome IN ('transmitted','received','accepted','rejected','paid')),
  reason_code        text,
  reason             text,
  paid_amount        numeric(14,2),
  approved_quantity  int,
  protocol_number    text,
  processed_at       timestamptz NOT NULL,
  source_system      text NOT NULL,
  source_record_id   text NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (record_id IS NOT NULL OR batch_id IS NOT NULL)
);
CREATE UNIQUE INDEX production_outcome_idempotency_idx
  ON production.production_outcome (tenant_id, source_system, source_record_id, outcome, coalesce(record_id, batch_id));
CREATE INDEX production_outcome_record_idx ON production.production_outcome (record_id, processed_at);

CREATE TABLE production.production_source_link (
  id                     text PRIMARY KEY,                    -- link_<ULID>
  tenant_id              text NOT NULL,
  record_id              text NOT NULL REFERENCES production.production_record(id),
  source_system          text NOT NULL,
  connector              text,
  source_record_id       text NOT NULL,
  source_record_version  text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_system, source_record_id)
);
CREATE INDEX production_source_link_record_idx ON production.production_source_link (record_id);

-- Prazos de apresentação por competência (PRO-007). tenant_id NULL = global (seed);
-- linha do tenant sobrepõe. Seed: dia 10 do mês seguinte, 23:59:59 America/Sao_Paulo.
CREATE TABLE production.production_deadline (
  id           text PRIMARY KEY,                              -- pdl_<competência>[_<tenant>]
  tenant_id    text,
  competence   text NOT NULL CHECK (competence ~ '^[0-9]{4}(0[1-9]|1[0-2])$'),
  deadline_at  timestamptz NOT NULL,
  alert_days   int[] NOT NULL DEFAULT '{5,1}',
  note         text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX production_deadline_scope_idx ON production.production_deadline (coalesce(tenant_id, ''), competence);

INSERT INTO production.production_deadline (id, tenant_id, competence, deadline_at, note)
SELECT 'pdl_' || to_char(m, 'YYYYMM'), NULL, to_char(m, 'YYYYMM'),
       make_timestamptz(extract(year from m + interval '1 month')::int,
                        extract(month from m + interval '1 month')::int, 10, 23, 59, 59, 'America/Sao_Paulo'),
       'seed: dia 10 do mês seguinte'
FROM generate_series(date '2024-01-01', date '2028-12-01', interval '1 month') AS m;

-- Alertas de prazo já emitidos (D-5, D-1) — idempotência do CompetenceDeadlineJob
CREATE TABLE production.production_deadline_alert (
  tenant_id    text NOT NULL,
  competence   text NOT NULL,
  alert        text NOT NULL,                                 -- D-5, D-1
  task_id      text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, competence, alert)
);

-- Tenants com produção em aberto: o job de prazos roda sem tenant (SECURITY DEFINER)
CREATE OR REPLACE FUNCTION production.active_tenants()
  RETURNS SETOF text
  LANGUAGE sql SECURITY DEFINER STABLE
AS $$
  SELECT DISTINCT tenant_id FROM production.production_record
   WHERE status IN ('generated','pending','validated','corrected')
$$;
REVOKE ALL ON FUNCTION production.active_tenants() FROM PUBLIC;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['production_record','production_record_history','production_validation_issue',
                           'production_batch','production_batch_item','production_submission',
                           'production_outcome','production_source_link','production_deadline_alert'] LOOP
    EXECUTE format('ALTER TABLE production.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE production.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON production.%I USING (tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;
ALTER TABLE production.production_deadline ENABLE ROW LEVEL SECURITY;
ALTER TABLE production.production_deadline FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_or_global ON production.production_deadline
  USING (tenant_id IS NULL OR tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

GRANT USAGE ON SCHEMA production TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA production TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA production GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
GRANT EXECUTE ON FUNCTION production.active_tenants() TO sus_nexus_app;

-- ---------------------------------------------------------------------
-- Pré-auditoria: rule_set production-validation v1 ("configuração antes de
-- código", plano §8.3). Cada regra: id, severidade, campo, mensagem e condição
-- de VIOLAÇÃO ("when") sobre fatos calculados pelo core (RuleEvaluator:
-- eq/ne/gt/ge/lt/le/in/contains_any/is_true/is_false/present). Fato ausente
-- nunca dispara a regra. Fatos: kind, requires_citizen, citizen_resolved,
-- citizen_identifier_valid, cnes_registered, cnes_active,
-- procedure_valid_in_competence, cbo_exists, cbo_compatible,
-- instrument_compatible, sex_compatible, age_compatible, quantity_within_max,
-- duplicate, attendance_in_competence, competence_open, apac_number_present,
-- aih_number_present, cid_valid, evidence_present, hospital_episode_linked,
-- professional_cbo_linked.
-- ---------------------------------------------------------------------
INSERT INTO platform.rule_set (id, name, description) VALUES
  ('rule_production_validation', 'production-validation',
   'Pré-auditoria de produção BPA-C/BPA-I/APAC/AIH (PRO-002..004): identificação, CNES, SIGTAP × CBO/sexo/idade/quantidade/instrumento, duplicidade, competência e evidência.');

INSERT INTO platform.rule_version
  (id, tenant_id, rule_set_id, version, status, definition, test_cases, approved_by, approved_at, effective_from, created_by)
VALUES (
  'rv_production_validation_1', NULL, 'rule_production_validation', '1', 'active',
  $json$
  {
    "kind": "validation_rules",
    "rules": [
      { "id": "citizen_unresolved", "severity": "error", "field": "citizen_ref",
        "message": "Cidadão obrigatório para o instrumento e não localizado no cadastro municipal (MPI)",
        "when": { "all": [ { "fact": "requires_citizen", "op": "is_true" }, { "fact": "citizen_resolved", "op": "is_false" } ] } },
      { "id": "citizen_identifier_invalid", "severity": "error", "field": "citizen_ref",
        "message": "CNS/CPF do cidadão ausente ou inválido (dígito verificador)",
        "when": { "all": [ { "fact": "requires_citizen", "op": "is_true" }, { "fact": "citizen_identifier_valid", "op": "is_false" } ] } },
      { "id": "cnes_not_registered", "severity": "error", "field": "cnes",
        "message": "Estabelecimento (CNES) não cadastrado na base de referência",
        "when": { "fact": "cnes_registered", "op": "is_false" } },
      { "id": "cnes_inactive", "severity": "error", "field": "cnes",
        "message": "Estabelecimento (CNES) inativo",
        "when": { "fact": "cnes_active", "op": "is_false" } },
      { "id": "procedure_invalid", "severity": "error", "field": "procedure_code",
        "message": "Procedimento SIGTAP inexistente ou não vigente na competência",
        "when": { "fact": "procedure_valid_in_competence", "op": "is_false" } },
      { "id": "cbo_unknown", "severity": "error", "field": "professional_cbo",
        "message": "CBO inexistente na tabela de ocupações vigente",
        "when": { "fact": "cbo_exists", "op": "is_false" } },
      { "id": "cbo_incompatible", "severity": "error", "field": "professional_cbo",
        "message": "CBO do profissional incompatível com o procedimento (SIGTAP)",
        "when": { "fact": "cbo_compatible", "op": "is_false" } },
      { "id": "instrument_incompatible", "severity": "error", "field": "kind",
        "message": "Instrumento de registro incompatível com o procedimento (SIGTAP)",
        "when": { "fact": "instrument_compatible", "op": "is_false" } },
      { "id": "sex_incompatible", "severity": "error", "field": "citizen_ref",
        "message": "Sexo do cidadão incompatível com o procedimento (SIGTAP)",
        "when": { "fact": "sex_compatible", "op": "is_false" } },
      { "id": "age_incompatible", "severity": "error", "field": "citizen_ref",
        "message": "Idade do cidadão fora da faixa permitida para o procedimento (SIGTAP)",
        "when": { "fact": "age_compatible", "op": "is_false" } },
      { "id": "quantity_exceeded", "severity": "error", "field": "quantity",
        "message": "Quantidade acima da máxima permitida para o procedimento (SIGTAP)",
        "when": { "all": [ { "fact": "kind", "op": "in", "value": ["bpa_i", "apac", "aih"] }, { "fact": "quantity_within_max", "op": "is_false" } ] } },
      { "id": "duplicate", "severity": "error", "field": "procedure_code",
        "message": "Registro duplicado: mesmo cidadão, procedimento, data e CNES",
        "when": { "fact": "duplicate", "op": "is_true" } },
      { "id": "attendance_outside_competence", "severity": "error", "field": "attendance_date",
        "message": "Data de atendimento posterior à competência ou anterior a 3 competências",
        "when": { "fact": "attendance_in_competence", "op": "is_false" } },
      { "id": "competence_closed", "severity": "error", "field": "competence",
        "message": "Competência encerrada: prazo de apresentação vencido",
        "when": { "fact": "competence_open", "op": "is_false" } },
      { "id": "apac_number_missing", "severity": "error", "field": "apac_number",
        "message": "APAC sem número de autorização",
        "when": { "all": [ { "fact": "kind", "op": "eq", "value": "apac" }, { "fact": "apac_number_present", "op": "is_false" } ] } },
      { "id": "aih_number_missing", "severity": "error", "field": "aih_number",
        "message": "AIH sem número de autorização",
        "when": { "all": [ { "fact": "kind", "op": "eq", "value": "aih" }, { "fact": "aih_number_present", "op": "is_false" } ] } },
      { "id": "cid_invalid", "severity": "error", "field": "cid_code",
        "message": "CID-10 inexistente",
        "when": { "fact": "cid_valid", "op": "is_false" } },
      { "id": "evidence_missing", "severity": "warning", "field": "encounter_ref",
        "message": "Sem atendimento/agendamento realizado correspondente na agenda (evidência)",
        "when": { "all": [ { "fact": "kind", "op": "in", "value": ["bpa_i", "apac"] }, { "fact": "evidence_present", "op": "is_false" } ] } },
      { "id": "hospital_episode_missing", "severity": "warning", "field": "hospital_episode_ref",
        "message": "AIH sem episódio hospitalar vinculado (conciliação com ADT)",
        "when": { "all": [ { "fact": "kind", "op": "eq", "value": "aih" }, { "fact": "hospital_episode_linked", "op": "is_false" } ] } },
      { "id": "professional_not_linked", "severity": "warning", "field": "professional_cbo",
        "message": "Profissional sem vínculo ativo com este CBO no CNES (base de referência)",
        "when": { "fact": "professional_cbo_linked", "op": "is_false" } }
    ]
  }
  $json$::jsonb,
  $json$
  [
    { "facts": { "kind": "bpa_i", "requires_citizen": true, "citizen_resolved": true, "citizen_identifier_valid": true, "cnes_registered": true, "cnes_active": true, "procedure_valid_in_competence": true, "cbo_exists": true, "cbo_compatible": true, "instrument_compatible": true, "quantity_within_max": true, "duplicate": false, "attendance_in_competence": true, "competence_open": true, "evidence_present": true }, "expected": [] },
    { "facts": { "kind": "bpa_i", "cbo_compatible": false, "evidence_present": false }, "expected": ["cbo_incompatible", "evidence_missing"] },
    { "facts": { "kind": "bpa_c", "quantity_within_max": false, "evidence_present": false }, "expected": [] },
    { "facts": { "kind": "aih", "aih_number_present": false, "hospital_episode_linked": false }, "expected": ["aih_number_missing", "hospital_episode_missing"] }
  ]
  $json$::jsonb,
  'seed', now(), now(), 'seed');
