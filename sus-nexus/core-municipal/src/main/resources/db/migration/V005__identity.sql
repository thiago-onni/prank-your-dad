-- =====================================================================
-- V005 — identity (MPI): cidadão, identificadores, histórico bitemporal,
--        endereço, contato, vínculos de origem, casos de fusão,
--        candidatos/evidências de matching e golden record com proveniência.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.citizen (
  id                     text PRIMARY KEY,                       -- cit_<ULID>
  tenant_id              text NOT NULL,
  status                 text NOT NULL CHECK (status IN ('active','merged','inactive')),
  merged_into_id         text NULL REFERENCES identity.citizen(id),
  registration_state     text NOT NULL CHECK (registration_state IN
                           ('validated','divergent','incomplete','duplicate','pending')),
  identity_confidence    text NOT NULL DEFAULT 'confirmed' CHECK (identity_confidence IN
                           ('confirmed','probable','pending','divergent')),
  legal_name             text NOT NULL,
  social_name            text,
  mother_name            text,
  father_name            text,
  birthdate              date NOT NULL,
  sex                    text NOT NULL DEFAULT 'unknown' CHECK (sex IN ('female','male','unknown')),
  race_color             text,
  nationality            text,
  deceased               boolean NOT NULL DEFAULT false,
  deceased_at            date,
  normalized_name        text NOT NULL,
  normalized_social_name text,
  normalized_mother_name text,
  health_unit_cnes       text,
  team_ine               text,
  microarea              text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  version                bigint NOT NULL DEFAULT 0
);
CREATE INDEX citizen_name_trgm_idx ON identity.citizen USING gin (normalized_name gin_trgm_ops);
CREATE INDEX citizen_social_trgm_idx ON identity.citizen USING gin (normalized_social_name gin_trgm_ops);
CREATE INDEX citizen_mother_trgm_idx ON identity.citizen USING gin (normalized_mother_name gin_trgm_ops);
CREATE INDEX citizen_birthdate_idx ON identity.citizen (tenant_id, birthdate);
CREATE INDEX citizen_demo_idx ON identity.citizen (tenant_id, normalized_name, birthdate, normalized_mother_name);
CREATE INDEX citizen_merged_into_idx ON identity.citizen (merged_into_id) WHERE merged_into_id IS NOT NULL;

CREATE TABLE identity.citizen_identifier (
  id             text PRIMARY KEY,                                -- cid_<ULID>
  tenant_id      text NOT NULL,
  citizen_id     text NOT NULL REFERENCES identity.citizen(id),
  system         text NOT NULL,                                   -- CNS, CPF, PEC, SISREG, ESUS_REGULACAO, HIS, AIH, APAC, LOCAL
  value_hash     text NOT NULL,                                   -- HMAC-SHA256 hex (busca sem expor)
  value_enc      bytea NOT NULL,                                  -- AES-256-GCM
  value_masked   text NOT NULL,
  status         text NOT NULL CHECK (status IN ('active','deprecated','invalid')),
  source_system  text NOT NULL,
  valid_from     timestamptz NOT NULL DEFAULT now(),
  valid_to       timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now()
);
-- MPI-012: um identificador ativo pertence a um único cidadão por tenant
CREATE UNIQUE INDEX citizen_identifier_active_uq
  ON identity.citizen_identifier (tenant_id, system, value_hash) WHERE status = 'active';
CREATE INDEX citizen_identifier_hash_idx ON identity.citizen_identifier (tenant_id, system, value_hash);
CREATE INDEX citizen_identifier_citizen_idx ON identity.citizen_identifier (citizen_id);

-- Histórico demográfico bitemporal simplificado
CREATE TABLE identity.citizen_demographic_history (
  id                text PRIMARY KEY,                             -- hist_<ULID>
  tenant_id         text NOT NULL,
  citizen_id        text NOT NULL REFERENCES identity.citizen(id),
  attributes        jsonb NOT NULL,
  valid_from        timestamptz NOT NULL,
  valid_to          timestamptz,
  recorded_from     timestamptz NOT NULL DEFAULT now(),
  recorded_to       timestamptz,
  source_system     text NOT NULL,
  source_record_id  text,
  change_reason     text
);
CREATE INDEX citizen_demo_hist_idx ON identity.citizen_demographic_history (citizen_id, recorded_from DESC);

CREATE TABLE identity.citizen_address (
  id             text PRIMARY KEY,                                -- addr_<ULID>
  tenant_id      text NOT NULL,
  citizen_id     text NOT NULL REFERENCES identity.citizen(id),
  street         text,
  number         text,
  complement     text,
  district       text,
  city_ibge      text,
  postal_code    text,
  is_current     boolean NOT NULL DEFAULT true,
  source_system  text NOT NULL,
  received_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX citizen_address_citizen_idx ON identity.citizen_address (citizen_id) WHERE is_current;

CREATE TABLE identity.citizen_contact (
  id             text PRIMARY KEY,                                -- ctt_<ULID>
  tenant_id      text NOT NULL,
  citizen_id     text NOT NULL REFERENCES identity.citizen(id),
  kind           text NOT NULL CHECK (kind IN ('phone','mobile','email')),
  value          text NOT NULL,
  value_norm     text NOT NULL,
  value_masked   text NOT NULL,
  preferred      boolean NOT NULL DEFAULT false,
  source_system  text NOT NULL,
  received_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX citizen_contact_citizen_idx ON identity.citizen_contact (citizen_id);

CREATE TABLE identity.citizen_source_link (
  id                    text PRIMARY KEY,                         -- link_<ULID>
  tenant_id             text NOT NULL,
  citizen_id            text NOT NULL REFERENCES identity.citizen(id),
  source_system         text NOT NULL,
  connector             text NOT NULL,
  source_record_id      text NOT NULL,
  source_record_version text,
  cnes                  text,
  received_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_system, source_record_id)
);
CREATE INDEX citizen_source_link_citizen_idx ON identity.citizen_source_link (citizen_id);

CREATE TABLE identity.citizen_merge_case (
  id                   text PRIMARY KEY,                          -- case_<ULID>
  tenant_id            text NOT NULL,
  status               text NOT NULL CHECK (status IN ('open','in_review','merged','rejected','unmerged')),
  reason               text,
  classification       text NOT NULL CHECK (classification IN ('confirmed','probable','pending','rejected','new')),
  score                numeric(10,4),
  rule_version         text,
  candidate_ids        jsonb NOT NULL,                            -- ["cit_...", "cit_..."]
  conflicts            jsonb NOT NULL DEFAULT '[]'::jsonb,        -- ["birthdate", "CNS", ...]
  opened_at            timestamptz NOT NULL DEFAULT now(),
  decided_at           timestamptz,
  decided_by           text,
  decision_reason      text,
  merge_id             text,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  version              bigint NOT NULL DEFAULT 0
);
CREATE INDEX citizen_merge_case_status_idx ON identity.citizen_merge_case (tenant_id, status, opened_at DESC);

CREATE TABLE identity.citizen_merge (
  id                   text PRIMARY KEY,                          -- merge_<ULID>
  tenant_id            text NOT NULL,
  case_id              text NOT NULL REFERENCES identity.citizen_merge_case(id),
  surviving_citizen_id text NOT NULL REFERENCES identity.citizen(id),
  merged_citizen_ids   jsonb NOT NULL,
  snapshot             jsonb NOT NULL,                            -- estado pré-fusão (para unmerge)
  reason               text NOT NULL,
  decided_by           text NOT NULL,
  merged_at            timestamptz NOT NULL DEFAULT now(),
  unmerged_at          timestamptz,
  unmerged_by          text,
  unmerge_reason       text
);

CREATE TABLE identity.citizen_match_candidate (
  id                    text PRIMARY KEY,                         -- mc_<ULID>
  tenant_id             text NOT NULL,
  case_id               text REFERENCES identity.citizen_merge_case(id),
  incoming_citizen_id   text NOT NULL REFERENCES identity.citizen(id),
  candidate_citizen_id  text NOT NULL REFERENCES identity.citizen(id),
  method                text NOT NULL,
  classification        text NOT NULL,
  score                 numeric(10,4),
  rule_version          text,
  created_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX citizen_match_candidate_case_idx ON identity.citizen_match_candidate (case_id);

CREATE TABLE identity.citizen_match_evidence (
  id                 text PRIMARY KEY,                            -- me_<ULID>
  tenant_id          text NOT NULL,
  match_candidate_id text NOT NULL REFERENCES identity.citizen_match_candidate(id),
  attribute          text NOT NULL,
  comparison         text NOT NULL,
  agreement          text NOT NULL CHECK (agreement IN ('agree','disagree','missing','partial')),
  weight             numeric(10,4) NOT NULL DEFAULT 0
);
CREATE INDEX citizen_match_evidence_candidate_idx ON identity.citizen_match_evidence (match_candidate_id);

CREATE TABLE identity.citizen_golden_record_attribute (
  id                text PRIMARY KEY,                             -- gra_<ULID>
  tenant_id         text NOT NULL,
  citizen_id        text NOT NULL REFERENCES identity.citizen(id),
  attribute         text NOT NULL,
  value             text,
  source_system     text NOT NULL,
  source_record_id  text,
  received_at       timestamptz NOT NULL DEFAULT now(),
  confidence        numeric(4,3) NOT NULL DEFAULT 1.0,
  UNIQUE (tenant_id, citizen_id, attribute)
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['citizen','citizen_identifier','citizen_demographic_history','citizen_address',
                           'citizen_contact','citizen_source_link','citizen_merge_case','citizen_merge',
                           'citizen_match_candidate','citizen_match_evidence','citizen_golden_record_attribute'] LOOP
    EXECUTE format('ALTER TABLE identity.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE identity.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON identity.%I USING (tenant_id = platform.current_tenant()) WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA identity TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO sus_nexus_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA identity TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity GRANT USAGE, SELECT ON SEQUENCES TO sus_nexus_app;
