-- =====================================================================
-- V018 — consent (mínimo): consentimentos por finalidade e preferências de
--        comunicação do cidadão. Valores de contato NUNCA em claro aqui: só
--        value_masked + value_hash (HMAC por tenant). API interna (consent.api);
--        REST fica para a Fase 3b.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS consent;

CREATE TABLE consent.consent (
  id           text PRIMARY KEY,                           -- cons_<ULID>
  tenant_id    text NOT NULL,
  citizen_id   text NOT NULL,
  purpose      text NOT NULL,                              -- communication, care_coordination, research...
  status       text NOT NULL CHECK (status IN ('granted','revoked')),
  channel      text,                                       -- sms, whatsapp, phone, email, in_person, app
  recorded_at  timestamptz NOT NULL,
  source       text NOT NULL,                              -- sistema/ator de origem
  actor_id     text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consent_citizen_idx ON consent.consent (tenant_id, citizen_id, purpose, recorded_at DESC);

CREATE TABLE consent.communication_preference (
  id            text PRIMARY KEY,                          -- cpref_<ULID>
  tenant_id     text NOT NULL,
  citizen_id    text NOT NULL,
  channel       text NOT NULL CHECK (channel IN ('sms','whatsapp','phone','email','app','letter')),
  value_masked  text,
  value_hash    text,
  preferred     boolean NOT NULL DEFAULT false,
  allowed       boolean NOT NULL DEFAULT true,
  source        text NOT NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, citizen_id, channel, value_hash)
);
CREATE INDEX communication_preference_citizen_idx ON consent.communication_preference (tenant_id, citizen_id, allowed);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['consent','communication_preference'] LOOP
    EXECUTE format('ALTER TABLE consent.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE consent.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON consent.%I USING (tenant_id = platform.current_tenant())'
      || ' WITH CHECK (tenant_id = platform.current_tenant())', t);
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA consent TO sus_nexus_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA consent TO sus_nexus_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA consent GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sus_nexus_app;
