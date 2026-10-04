-- =====================================================================
-- V015 — platform: conjuntos de regras versionados ("configuração antes de
--        código", plano §8.3). rule_set identifica a regra; rule_version guarda
--        a definição (jsonb — tabela de decisão restrita, sem código), o ciclo
--        draft → in_review → approved → active → revoked, approved_by e
--        effective_from. Linhas com tenant_id NULL são globais (seed); um tenant
--        pode sobrepor com versão própria (RLS tenant_or_global).
--        Seed: post-discharge-risk v1 (HOS-005).
--        Também: SLA de post_discharge_followup por prioridade (risco).
-- =====================================================================

CREATE TABLE platform.rule_set (
  id           text PRIMARY KEY,                           -- rule_<slug>
  name         text NOT NULL,
  description  text,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform.rule_version (
  id              text PRIMARY KEY,                        -- rv_<ULID> ou rv_<slug>_<versão>
  tenant_id       text,                                    -- NULL = global
  rule_set_id     text NOT NULL REFERENCES platform.rule_set(id),
  version         text NOT NULL,
  status          text NOT NULL CHECK (status IN ('draft','in_review','approved','active','revoked')),
  definition      jsonb NOT NULL,                          -- tabela de decisão (facts/ops restritos)
  test_cases      jsonb NOT NULL DEFAULT '[]'::jsonb,
  approved_by     text,
  approved_at     timestamptz,
  effective_from  timestamptz,
  created_by      text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, rule_set_id, version)
);
CREATE UNIQUE INDEX rule_version_one_active_idx
  ON platform.rule_version (coalesce(tenant_id, ''), rule_set_id) WHERE status = 'active';
CREATE INDEX rule_version_lookup_idx ON platform.rule_version (rule_set_id, status, effective_from);

ALTER TABLE platform.rule_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.rule_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_or_global ON platform.rule_version
  USING (tenant_id IS NULL OR tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

INSERT INTO platform.rule_set (id, name, description) VALUES
  ('rule_post_discharge_risk', 'post-discharge-risk',
   'Classificação de risco pós-alta (HOS-005): tabela de decisão sobre LOS, destino, reinternação, idade, linhas de cuidado e plano de seguimento.');

-- Tabela de decisão: primeira regra cujo bloco "any"/"all" é verdadeiro vence; senão "default".
-- Operadores permitidos: eq, ne, gt, ge, lt, le, in, contains_any, is_true, is_false.
INSERT INTO platform.rule_version
  (id, tenant_id, rule_set_id, version, status, definition, test_cases, approved_by, approved_at, effective_from, created_by)
VALUES (
  'rv_post_discharge_risk_1', NULL, 'rule_post_discharge_risk', '1', 'active',
  $json$
  {
    "kind": "decision_table",
    "result_key": "risk_level",
    "default": "low",
    "rules": [
      { "result": "high", "any": [
          { "fact": "length_of_stay_days", "op": "ge", "value": 7 },
          { "fact": "disposition", "op": "eq", "value": "home_with_care" },
          { "fact": "readmission_within_30d", "op": "is_true" },
          { "fact": "age_years", "op": "ge", "value": 75 },
          { "fact": "care_lines", "op": "contains_any", "value": ["oncologia", "saude_mental"] }
      ] },
      { "result": "medium", "any": [
          { "fact": "length_of_stay_days", "op": "ge", "value": 3 },
          { "fact": "followup_plan_present", "op": "is_false" }
      ] }
    ]
  }
  $json$::jsonb,
  $json$
  [
    { "facts": { "length_of_stay_days": 9, "disposition": "home", "readmission_within_30d": false, "age_years": 40, "care_lines": [], "followup_plan_present": true }, "expected": "high" },
    { "facts": { "length_of_stay_days": 1, "disposition": "home", "readmission_within_30d": false, "age_years": 80, "care_lines": [], "followup_plan_present": true }, "expected": "high" },
    { "facts": { "length_of_stay_days": 4, "disposition": "home", "readmission_within_30d": false, "age_years": 40, "care_lines": [], "followup_plan_present": true }, "expected": "medium" },
    { "facts": { "length_of_stay_days": 1, "disposition": "home", "readmission_within_30d": false, "age_years": 40, "care_lines": [], "followup_plan_present": false }, "expected": "medium" },
    { "facts": { "length_of_stay_days": 1, "disposition": "home", "readmission_within_30d": false, "age_years": 40, "care_lines": ["hipertensao"], "followup_plan_present": true }, "expected": "low" }
  ]
  $json$::jsonb,
  'seed', now(), now(), 'seed');

GRANT SELECT, INSERT, UPDATE, DELETE ON platform.rule_set, platform.rule_version TO sus_nexus_app;

-- SLA do contato pós-alta por prioridade (= risco): high 24 h, medium 72 h, low 7 d
INSERT INTO tasks.sla_policy (id, task_type, priority, due_in, escalate_after, escalate_to_kind, escalate_to_id) VALUES
  ('sla_post_discharge_followup_high',   'post_discharge_followup', 'high',   interval '24 hours', interval '12 hours', 'queue', 'busca_ativa'),
  ('sla_post_discharge_followup_medium', 'post_discharge_followup', 'medium', interval '72 hours', interval '24 hours', 'queue', 'busca_ativa'),
  ('sla_post_discharge_followup_low',    'post_discharge_followup', 'low',    interval '7 days',   interval '3 days',   'queue', 'busca_ativa');
