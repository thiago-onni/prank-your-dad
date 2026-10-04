-- =====================================================================
-- V017 — careplan: protocolos configuráveis e versionados (CUI-009), planos de
--        cuidado instanciados a partir da versão vigente (CUI-001), itens
--        previstos (CUI-002) e lacunas de cuidado / busca ativa (CUI-003/004).
--        Protocolos com tenant_id NULL são globais (seed); um tenant pode criar
--        versões próprias (RLS tenant_or_global). Só uma versão 'active' por
--        protocolo e escopo (índice parcial).
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS careplan;

CREATE TABLE careplan.protocol (
  id          text PRIMARY KEY,                            -- prot_<slug|ULID>
  tenant_id   text,                                        -- NULL = global
  care_line   text NOT NULL,
  name        text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, care_line, name)
);

CREATE TABLE careplan.protocol_version (
  id                      text PRIMARY KEY,                -- pv_<ULID>
  tenant_id               text,                            -- NULL = global
  protocol_id             text NOT NULL REFERENCES careplan.protocol(id),
  version                 text NOT NULL,
  status                  text NOT NULL CHECK (status IN ('draft','in_review','approved','active','revoked')),
  description             text,
  eligibility             jsonb NOT NULL DEFAULT '{}'::jsonb,   -- expressão restrita (idade, sexo)
  items                   jsonb NOT NULL DEFAULT '[]'::jsonb,   -- ProtocolItemRule[]
  test_cases              jsonb NOT NULL DEFAULT '[]'::jsonb,
  lost_to_followup_days   int NOT NULL DEFAULT 90,
  approved_by             text,
  approved_at             timestamptz,
  effective_from          timestamptz,
  created_by              text,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, protocol_id, version)
);
CREATE UNIQUE INDEX protocol_version_one_active_idx
  ON careplan.protocol_version (coalesce(tenant_id, ''), protocol_id) WHERE status = 'active';
CREATE INDEX protocol_version_lookup_idx ON careplan.protocol_version (protocol_id, status);

CREATE TABLE careplan.care_plan (
  id                          text PRIMARY KEY,            -- cp_<ULID>
  tenant_id                   text NOT NULL,
  citizen_id                  text NOT NULL,
  care_line                   text NOT NULL,
  status                      text NOT NULL CHECK (status IN ('active','on_hold','completed','cancelled')),
  protocol_id                 text NOT NULL,
  protocol_version_id         text NOT NULL,
  protocol_version            text NOT NULL,
  health_unit_cnes            text CHECK (health_unit_cnes IS NULL OR health_unit_cnes ~ '^[0-9]{7}$'),
  team_ine                    text,
  microarea                   text,
  responsible_professional_id text,
  origin_kind                 text CHECK (origin_kind IS NULL OR origin_kind IN ('professional','rule','workflow','hospital_discharge')),
  origin_id                   text,
  start_at                    timestamptz NOT NULL,
  last_care_event_at          timestamptz,
  closed_reason               text,
  closed_at                   timestamptz,
  created_by                  text,
  created_at                  timestamptz NOT NULL DEFAULT now(),
  updated_at                  timestamptz NOT NULL DEFAULT now(),
  version                     bigint NOT NULL DEFAULT 0
);
CREATE INDEX care_plan_citizen_idx ON careplan.care_plan (tenant_id, citizen_id, status);
CREATE INDEX care_plan_team_idx ON careplan.care_plan (tenant_id, team_ine, status);
CREATE INDEX care_plan_cnes_idx ON careplan.care_plan (tenant_id, health_unit_cnes, status);
CREATE INDEX care_plan_line_idx ON careplan.care_plan (tenant_id, care_line, status);

CREATE TABLE careplan.care_plan_item (
  id                 text PRIMARY KEY,                     -- cpi_<ULID>
  tenant_id          text NOT NULL,
  care_plan_id       text NOT NULL REFERENCES careplan.care_plan(id),
  citizen_id         text NOT NULL,
  sequence           int NOT NULL DEFAULT 0,
  kind               text NOT NULL CHECK (kind IN ('consultation','exam','vaccine','return','home_visit','procedure','education','other')),
  title              text NOT NULL,
  code               text,
  code_system        text,
  expected_by        timestamptz,
  periodicity_days   int,
  gap_after_days     int NOT NULL DEFAULT 0,
  priority           text NOT NULL DEFAULT 'medium' CHECK (priority IN ('low','medium','high','urgent')),
  status             text NOT NULL CHECK (status IN ('planned','scheduled','done','missed','cancelled')),
  performed_at       timestamptz,
  evidence_ref       text,
  note               text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX care_plan_item_plan_idx ON careplan.care_plan_item (care_plan_id, sequence);
CREATE INDEX care_plan_item_due_idx ON careplan.care_plan_item (tenant_id, status, expected_by);
CREATE INDEX care_plan_item_citizen_idx ON careplan.care_plan_item (tenant_id, citizen_id, status);

CREATE TABLE careplan.care_gap (
  id                text PRIMARY KEY,                      -- gap_<ULID>
  tenant_id         text NOT NULL,
  citizen_id        text NOT NULL,
  care_plan_id      text,
  item_id           text,
  care_line         text NOT NULL,
  gap_kind          text NOT NULL CHECK (gap_kind IN
                      ('consultation_overdue','exam_overdue','vaccine_overdue','return_overdue','no_contact','lost_to_followup','post_discharge_no_contact')),
  status            text NOT NULL CHECK (status IN ('open','resolved')),
  expected_by       timestamptz,
  protocol_id       text,
  protocol_version  text NOT NULL,
  health_unit_cnes  text,
  team_ine          text,
  microarea         text,
  task_id           text,
  origin_ref        text,                                  -- ex.: episódio hospitalar (pós-alta)
  detected_at       timestamptz NOT NULL,
  resolved_at       timestamptz,
  resolution        text CHECK (resolution IS NULL OR resolution IN
                      ('performed','scheduled','contact_made','refused','moved','deceased','not_found','cancelled')),
  note              text,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX care_gap_open_item_idx ON careplan.care_gap (item_id) WHERE status = 'open' AND item_id IS NOT NULL;
CREATE UNIQUE INDEX care_gap_open_plan_kind_idx ON careplan.care_gap (care_plan_id, gap_kind) WHERE status = 'open' AND item_id IS NULL AND care_plan_id IS NOT NULL;
CREATE UNIQUE INDEX care_gap_open_origin_idx ON careplan.care_gap (origin_ref, gap_kind) WHERE status = 'open' AND origin_ref IS NOT NULL;
CREATE INDEX care_gap_list_idx ON careplan.care_gap (tenant_id, status, health_unit_cnes, team_ine, microarea);
CREATE INDEX care_gap_citizen_idx ON careplan.care_gap (tenant_id, citizen_id, status);

-- Tenants com planos ativos: o job de detecção roda sem tenant (SECURITY DEFINER)
CREATE OR REPLACE FUNCTION careplan.active_tenants()
  RETURNS SETOF text
  LANGUAGE sql SECURITY DEFINER STABLE
AS $$
  SELECT DISTINCT tenant_id FROM careplan.care_plan WHERE status = 'active'
$$;
REVOKE ALL ON FUNCTION careplan.active_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careplan.active_tenants() TO sus_nexus_app;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['care_plan','care_plan_item','care_gap'] LOOP
    EXECUTE format('ALTER TABLE careplan.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE careplan.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON careplan.%I USING (tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
  FOREACH t IN ARRAY ARRAY['protocol','protocol_version'] LOOP
    EXECUTE format('ALTER TABLE careplan.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE careplan.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_or_global ON careplan.%I USING (tenant_id IS NULL OR tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- Seed: três protocolos globais ativos (v1). Condições/elegibilidade são
-- expressões restritas sobre atributos do cidadão (age_years, sex).
-- ---------------------------------------------------------------------
INSERT INTO careplan.protocol (id, tenant_id, care_line, name) VALUES
  ('prot_gestante',    NULL, 'gestante',    'Pré-natal e puerpério'),
  ('prot_hipertensao', NULL, 'hipertensao', 'Hipertensão arterial'),
  ('prot_diabetes',    NULL, 'diabetes',    'Diabetes mellitus');

INSERT INTO careplan.protocol_version
  (id, tenant_id, protocol_id, version, status, description, eligibility, items, test_cases, lost_to_followup_days, approved_by, approved_at, effective_from, created_by)
VALUES
  ('pv_gestante_1', NULL, 'prot_gestante', '1', 'active',
   'Consultas pré-natal mensais, exames do 1º e 3º trimestres e retorno puerperal em 7 dias.',
   '{"all": [{"fact": "sex", "op": "eq", "value": "female"}, {"fact": "age_years", "op": "ge", "value": 10}]}'::jsonb,
   $json$[
     {"kind": "consultation", "title": "Consulta pré-natal", "code": "0301010110", "code_system": "SIGTAP", "due_in_days": 30, "periodicity_days": 30, "gap_after_days": 15, "priority": "high"},
     {"kind": "exam", "title": "Exames do 1º trimestre", "due_in_days": 30, "gap_after_days": 30, "priority": "high"},
     {"kind": "exam", "title": "Exames do 3º trimestre", "due_in_days": 180, "gap_after_days": 30, "priority": "high"},
     {"kind": "return", "title": "Retorno puerperal (até 7 dias após o parto)", "due_in_days": 287, "gap_after_days": 7, "priority": "high"}
   ]$json$::jsonb,
   '[{"facts": {"sex": "female", "age_years": 25}, "expected_items": 4}, {"facts": {"sex": "male", "age_years": 25}, "expected_eligible": false}]'::jsonb,
   90, 'seed', now(), now(), 'seed'),
  ('pv_hipertensao_1', NULL, 'prot_hipertensao', '1', 'active',
   'Consulta semestral e exames anuais.',
   '{"all": [{"fact": "age_years", "op": "ge", "value": 18}]}'::jsonb,
   $json$[
     {"kind": "consultation", "title": "Consulta de acompanhamento (semestral)", "code": "0301010064", "code_system": "SIGTAP", "due_in_days": 180, "periodicity_days": 180, "gap_after_days": 30, "priority": "medium"},
     {"kind": "exam", "title": "Exames anuais (perfil lipídico, creatinina, potássio, glicemia)", "due_in_days": 365, "periodicity_days": 365, "gap_after_days": 60, "priority": "medium"}
   ]$json$::jsonb,
   '[{"facts": {"sex": "male", "age_years": 60}, "expected_items": 2}]'::jsonb,
   90, 'seed', now(), now(), 'seed'),
  ('pv_diabetes_1', NULL, 'prot_diabetes', '1', 'active',
   'Consulta quadrimestral, HbA1c semestral e fundo de olho anual.',
   '{"all": [{"fact": "age_years", "op": "ge", "value": 0}]}'::jsonb,
   $json$[
     {"kind": "consultation", "title": "Consulta de acompanhamento (quadrimestral)", "code": "0301010064", "code_system": "SIGTAP", "due_in_days": 120, "periodicity_days": 120, "gap_after_days": 30, "priority": "medium"},
     {"kind": "exam", "title": "Hemoglobina glicada (semestral)", "code": "0202010473", "code_system": "SIGTAP", "due_in_days": 180, "periodicity_days": 180, "gap_after_days": 30, "priority": "medium"},
     {"kind": "exam", "title": "Fundo de olho (anual)", "code": "0211060100", "code_system": "SIGTAP", "due_in_days": 365, "periodicity_days": 365, "gap_after_days": 60, "priority": "medium"}
   ]$json$::jsonb,
   '[{"facts": {"sex": "female", "age_years": 55}, "expected_items": 3}]'::jsonb,
   90, 'seed', now(), now(), 'seed');

GRANT USAGE ON SCHEMA careplan TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA careplan TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA careplan GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
