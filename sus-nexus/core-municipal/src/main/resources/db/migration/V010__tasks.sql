-- =====================================================================
-- V010 — tasks: tarefas de cuidado (care_task), histórico e políticas de SLA.
--        sla_policy: linhas com tenant_id NULL são padrões globais (seed);
--        linhas com tenant_id são sobrescritas municipais (RLS).
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS tasks;

CREATE TABLE tasks.sla_policy (
  id                 text PRIMARY KEY,                     -- sla_<código>
  tenant_id          text,                                 -- NULL = padrão global
  task_type          text NOT NULL,
  priority           text CHECK (priority IS NULL OR priority IN ('low','medium','high','urgent')),
  due_in             interval NOT NULL,
  escalate_after     interval,
  escalate_to_kind   text CHECK (escalate_to_kind IS NULL OR escalate_to_kind IN ('user','team','health_unit','queue')),
  escalate_to_id     text,
  policy_version     text NOT NULL DEFAULT '1.0',
  effective_from     timestamptz NOT NULL DEFAULT now(),
  effective_to       timestamptz,
  active             boolean NOT NULL DEFAULT true,
  created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sla_policy_lookup_idx ON tasks.sla_policy (task_type, priority, active);

CREATE TABLE tasks.care_task (
  id              text PRIMARY KEY,                        -- task_<ULID>
  tenant_id       text NOT NULL,
  task_type       text NOT NULL CHECK (task_type IN
                    ('mpi_review','active_search','no_show_recovery','exam_not_scheduled','exam_result_followup',
                     'post_discharge_followup','regulation_pending_document','production_issue',
                     'integration_error','care_gap','generic')),
  status          text NOT NULL CHECK (status IN ('open','assigned','in_progress','completed','cancelled','escalated')),
  priority        text NOT NULL CHECK (priority IN ('low','medium','high','urgent')),
  title           text NOT NULL,
  description     text,
  citizen_id      text,
  assignee_kind   text CHECK (assignee_kind IS NULL OR assignee_kind IN ('user','team','health_unit','queue')),
  assignee_id     text,
  due_at          timestamptz,
  sla_policy_id   text,
  sla_breached_at timestamptz,
  origin_kind     text CHECK (origin_kind IS NULL OR origin_kind IN ('workflow','agent','user','rule','connector')),
  origin_id       text,
  origin_version  text,
  outcome         text,
  reason          text,
  created_by      text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  completed_at    timestamptz,
  version         bigint NOT NULL DEFAULT 0
);
CREATE INDEX care_task_status_idx ON tasks.care_task (tenant_id, status, due_at);
CREATE INDEX care_task_citizen_idx ON tasks.care_task (tenant_id, citizen_id);
CREATE INDEX care_task_assignee_idx ON tasks.care_task (tenant_id, assignee_kind, assignee_id, status);
CREATE INDEX care_task_origin_idx ON tasks.care_task (tenant_id, origin_kind, origin_id);
CREATE INDEX care_task_type_idx ON tasks.care_task (tenant_id, task_type, status);

CREATE TABLE tasks.task_history (
  id              text PRIMARY KEY,                        -- th_<ULID>
  tenant_id       text NOT NULL,
  task_id         text NOT NULL REFERENCES tasks.care_task(id),
  action          text NOT NULL,                           -- created, assign, start, complete, cancel, escalate, sla_breached
  previous_status text,
  status          text NOT NULL,
  assignee_kind   text,
  assignee_id     text,
  outcome         text,
  reason          text,
  actor_id        text NOT NULL,
  occurred_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX task_history_idx ON tasks.task_history (task_id, occurred_at);
CREATE TRIGGER task_history_append_only
  BEFORE UPDATE OR DELETE ON tasks.task_history
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

-- RLS: care_task/task_history por tenant; sla_policy visível quando global OU do tenant
ALTER TABLE tasks.care_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks.care_task FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tasks.care_task
  USING (tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

ALTER TABLE tasks.task_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks.task_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tasks.task_history
  USING (tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

ALTER TABLE tasks.sla_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks.sla_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_or_global ON tasks.sla_policy
  USING (tenant_id IS NULL OR tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

-- Seed: políticas padrão (globais)
INSERT INTO tasks.sla_policy (id, task_type, priority, due_in, escalate_after, escalate_to_kind, escalate_to_id) VALUES
  ('sla_mpi_review',                 'mpi_review',                  NULL,     interval '5 days',  interval '2 days',  'queue', 'cadastro_mestre_gestor'),
  ('sla_no_show_recovery',           'no_show_recovery',            NULL,     interval '3 days',  interval '2 days',  'queue', 'coordenacao_aps'),
  ('sla_active_search',              'active_search',               NULL,     interval '7 days',  interval '3 days',  'queue', 'coordenacao_aps'),
  ('sla_exam_not_scheduled',         'exam_not_scheduled',          NULL,     interval '10 days', interval '5 days',  'queue', 'regulacao'),
  ('sla_exam_result_followup',       'exam_result_followup',        NULL,     interval '7 days',  interval '3 days',  'queue', 'coordenacao_aps'),
  ('sla_post_discharge_followup',    'post_discharge_followup',     NULL,     interval '7 days',  interval '2 days',  'queue', 'coordenacao_aps'),
  ('sla_regulation_pending_document','regulation_pending_document', NULL,     interval '5 days',  interval '2 days',  'queue', 'regulacao'),
  ('sla_production_issue',           'production_issue',            NULL,     interval '10 days', interval '5 days',  'queue', 'faturamento'),
  ('sla_integration_error',          'integration_error',           NULL,     interval '2 days',  interval '1 day',   'queue', 'integracao'),
  ('sla_care_gap',                   'care_gap',                    NULL,     interval '30 days', interval '15 days', 'queue', 'coordenacao_aps'),
  ('sla_generic_urgent',             'generic',                     'urgent', interval '1 day',   interval '4 hours', 'queue', 'coordenacao_aps'),
  ('sla_generic',                    'generic',                     NULL,     interval '7 days',  interval '3 days',  'queue', 'coordenacao_aps');

GRANT USAGE ON SCHEMA tasks TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tasks TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA tasks GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
