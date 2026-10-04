-- =====================================================================
-- V003 — reference: organização, unidade (CNES), profissional, vínculo,
--        equipe (INE), território e microárea. Tudo com tenant + RLS.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS reference;

CREATE TABLE reference.organization (
  id          text PRIMARY KEY,                    -- org_<ULID>
  tenant_id   text NOT NULL,
  name        text NOT NULL,
  kind        text NOT NULL DEFAULT 'health_secretariat',
  cnpj        text,
  active      boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  version     bigint NOT NULL DEFAULT 0
);

CREATE TABLE reference.health_unit (
  id               text PRIMARY KEY,               -- hu_<ULID>
  tenant_id        text NOT NULL,
  organization_id  text REFERENCES reference.organization(id),
  cnes             text NOT NULL CHECK (cnes ~ '^[0-9]{7}$'),
  name             text NOT NULL,
  kind_code        text,
  kind_description text,
  address          text,
  active           boolean NOT NULL DEFAULT true,
  source_system    text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          bigint NOT NULL DEFAULT 0,
  UNIQUE (tenant_id, cnes)
);
CREATE INDEX health_unit_name_trgm_idx
  ON reference.health_unit USING gin (platform.immutable_unaccent(lower(name)) gin_trgm_ops);

CREATE TABLE reference.professional (
  id            text PRIMARY KEY,                  -- prof_<ULID>
  tenant_id     text NOT NULL,
  name          text NOT NULL,
  cns_hash      text,
  cns_masked    text,
  cpf_hash      text,
  cpf_masked    text,
  active        boolean NOT NULL DEFAULT true,
  source_system text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  version       bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX professional_cns_idx ON reference.professional (tenant_id, cns_hash) WHERE cns_hash IS NOT NULL;

CREATE TABLE reference.care_team (
  id              text PRIMARY KEY,                -- team_<ULID>
  tenant_id       text NOT NULL,
  health_unit_id  text NOT NULL REFERENCES reference.health_unit(id),
  ine             text NOT NULL,
  name            text,
  team_type       text,
  active          boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, ine)
);

CREATE TABLE reference.professional_role (
  id              text PRIMARY KEY,                -- role_<ULID>
  tenant_id       text NOT NULL,
  professional_id text NOT NULL REFERENCES reference.professional(id),
  health_unit_id  text NOT NULL REFERENCES reference.health_unit(id),
  care_team_id    text REFERENCES reference.care_team(id),
  cbo             text NOT NULL CHECK (cbo ~ '^[0-9]{6}$'),
  active          boolean NOT NULL DEFAULT true,
  valid_from      timestamptz NOT NULL DEFAULT now(),
  valid_to        timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reference.territory (
  id              text PRIMARY KEY,                -- terr_<ULID>
  tenant_id       text NOT NULL,
  health_unit_id  text NOT NULL REFERENCES reference.health_unit(id),
  name            text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reference.microarea (
  id              text PRIMARY KEY,                -- micro_<ULID>
  tenant_id       text NOT NULL,
  territory_id    text NOT NULL REFERENCES reference.territory(id),
  care_team_id    text REFERENCES reference.care_team(id),
  code            text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, territory_id, code)
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['organization','health_unit','professional','care_team','professional_role','territory','microarea'] LOOP
    EXECUTE format('ALTER TABLE reference.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE reference.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON reference.%I USING (tenant_id = platform.current_tenant()) WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA reference TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reference TO sus_nexus_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA reference TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA reference GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA reference GRANT USAGE, SELECT ON SEQUENCES TO sus_nexus_app;
