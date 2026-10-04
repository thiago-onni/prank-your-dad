-- =====================================================================
-- V016 — hospital: episódios (internação/urgência), movimentações ADT
--        (append-only), alta (metadados do sumário; nunca o conteúdo),
--        contrarreferência e vínculo de origem (HOS-001..010).
--        principal_diagnosis_cid: classificado highly_restricted quando o CID
--        pertence a capítulos sensíveis (sus.privacy.highly-restricted-cid-prefixes).
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS hospital;

CREATE TABLE hospital.hospital_episode (
  id                          text PRIMARY KEY,            -- hep_<ULID>
  tenant_id                   text NOT NULL,
  citizen_id                  text NOT NULL,
  hospital_cnes               text NOT NULL CHECK (hospital_cnes ~ '^[0-9]{7}$'),
  episode_class               text NOT NULL CHECK (episode_class IN ('inpatient','emergency','observation','day_hospital')),
  status                      text NOT NULL CHECK (status IN ('admitted','in_progress','transferred','discharged','deceased','cancelled')),
  admitted_at                 timestamptz NOT NULL,
  discharged_at               timestamptz,
  length_of_stay_days         int,
  disposition                 text CHECK (disposition IS NULL OR disposition IN ('home','home_with_care','transfer','against_advice','deceased','other')),
  ward                        text,
  bed                         text,
  attending_professional_id   text,
  admission_source            text CHECK (admission_source IS NULL OR admission_source IN ('emergency','regulation','transfer','elective','other')),
  regulation_request_id       text,
  principal_diagnosis_cid     text,                        -- código CID-10; sem descrição
  cid_highly_restricted       boolean NOT NULL DEFAULT false,
  aih_number                  text,
  procedures_count            int,
  readmission_within_30d      boolean NOT NULL DEFAULT false,
  previous_episode_id         text,
  reference_health_unit_cnes  text,
  reference_team_ine          text,
  reference_microarea         text,
  risk_level                  text CHECK (risk_level IS NULL OR risk_level IN ('low','medium','high')),
  risk_rule_version           text,
  care_lines                  text[] NOT NULL DEFAULT '{}',
  followup_plan_present       boolean,
  followup_due_days           int,
  followup_status             text CHECK (followup_status IS NULL OR followup_status IN ('pending','contacted','scheduled','closed','escalated')),
  followup_task_id            text,
  followup_due_at             timestamptz,
  followup_outcome            text,
  followup_contacted_at       timestamptz,
  followup_care_plan_id       text,
  summary_document_ref        text,
  summary_document_sha256     text,
  source_system               text NOT NULL,
  source_record_id            text NOT NULL,
  source_record_version       text,
  created_at                  timestamptz NOT NULL DEFAULT now(),
  updated_at                  timestamptz NOT NULL DEFAULT now(),
  version                     bigint NOT NULL DEFAULT 0
);
CREATE INDEX hospital_episode_citizen_idx ON hospital.hospital_episode (tenant_id, citizen_id, admitted_at DESC);
CREATE INDEX hospital_episode_hospital_idx ON hospital.hospital_episode (tenant_id, hospital_cnes, status);
CREATE INDEX hospital_episode_reference_idx ON hospital.hospital_episode (tenant_id, reference_health_unit_cnes, followup_status);
CREATE INDEX hospital_episode_discharged_idx ON hospital.hospital_episode (tenant_id, discharged_at);

-- Movimentações ADT: append-only
CREATE TABLE hospital.hospital_bed_movement (
  id                         text PRIMARY KEY,             -- hbm_<ULID>
  tenant_id                  text NOT NULL,
  episode_id                 text NOT NULL REFERENCES hospital.hospital_episode(id),
  movement                   text NOT NULL CHECK (movement IN ('admit','transfer','bed_change','discharge','death','cancel')),
  occurred_at                timestamptz NOT NULL,
  recorded_at                timestamptz NOT NULL DEFAULT now(),
  ward                       text,
  bed                        text,
  previous_ward              text,
  previous_bed               text,
  attending_professional_id  text,
  reason                     text,
  source_system              text,
  actor_id                   text
);
CREATE INDEX hospital_bed_movement_idx ON hospital.hospital_bed_movement (episode_id, occurred_at);
CREATE TRIGGER hospital_bed_movement_append_only
  BEFORE UPDATE OR DELETE ON hospital.hospital_bed_movement
  FOR EACH STATEMENT EXECUTE FUNCTION platform.deny_mutation();

-- Alta: metadados + referência segura ao sumário (HOS-009/010); conteúdo NUNCA aqui
CREATE TABLE hospital.hospital_discharge (
  id                        text PRIMARY KEY,              -- hdis_<ULID>
  tenant_id                 text NOT NULL,
  episode_id                text NOT NULL REFERENCES hospital.hospital_episode(id),
  discharged_at             timestamptz NOT NULL,
  disposition               text NOT NULL,
  length_of_stay_days       int NOT NULL,
  procedures_count          int,
  followup_plan_present     boolean,
  followup_due_days         int,
  care_lines                text[] NOT NULL DEFAULT '{}',
  readmission_within_30d    boolean NOT NULL DEFAULT false,
  risk_level                text NOT NULL,
  risk_rule_version         text NOT NULL,
  risk_facts                jsonb NOT NULL DEFAULT '{}'::jsonb,   -- fatos usados pela regra (reprodutibilidade)
  summary_document_ref      text,
  summary_document_sha256   text,
  source_system             text NOT NULL,
  source_record_id          text,
  actor_id                  text,
  created_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX hospital_discharge_episode_idx ON hospital.hospital_discharge (episode_id, discharged_at);

CREATE TABLE hospital.counter_referral (
  id                        text PRIMARY KEY,              -- cref_<ULID>
  tenant_id                 text NOT NULL,
  episode_id                text NOT NULL REFERENCES hospital.hospital_episode(id),
  received_at               timestamptz NOT NULL,
  target_health_unit_cnes   text CHECK (target_health_unit_cnes IS NULL OR target_health_unit_cnes ~ '^[0-9]{7}$'),
  document_ref              text,
  document_sha256           text,
  recommendations_count     int,
  source_system             text NOT NULL,
  source_record_id          text,
  created_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX counter_referral_episode_idx ON hospital.counter_referral (episode_id, received_at);

CREATE TABLE hospital.hospital_episode_source_link (
  id                     text PRIMARY KEY,                 -- link_<ULID>
  tenant_id              text NOT NULL,
  episode_id             text NOT NULL REFERENCES hospital.hospital_episode(id),
  source_system          text NOT NULL,
  connector              text,
  source_record_id       text NOT NULL,
  source_record_version  text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_system, source_record_id)
);
CREATE INDEX hospital_episode_source_link_episode_idx ON hospital.hospital_episode_source_link (episode_id);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['hospital_episode','hospital_bed_movement','hospital_discharge','counter_referral','hospital_episode_source_link'] LOOP
    EXECUTE format('ALTER TABLE hospital.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE hospital.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON hospital.%I USING (tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA hospital TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA hospital TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA hospital GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
