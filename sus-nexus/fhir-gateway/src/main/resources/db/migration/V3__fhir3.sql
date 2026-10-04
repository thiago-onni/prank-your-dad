-- FHIR-3: metadados de Binary (conteúdo no object storage, nunca no banco) e índice de quantidade
-- (Observation.value-quantity). O delete lógico usa a coluna "deleted" já existente em fhir_resource
-- e fhir_resource_history; não há exclusão física (anonimização LGPD é processo separado).

CREATE TABLE fhir.fhir_binary (
  id            text        NOT NULL,
  tenant_id     text        NOT NULL,
  content_type  text        NOT NULL,
  size_bytes    bigint      NOT NULL,
  sha256        text        NOT NULL,
  storage_key   text        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id),
  CONSTRAINT fhir_binary_tenant_id_uq UNIQUE (tenant_id, id)
);

ALTER TABLE fhir.fhir_binary ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fhir.fhir_binary
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT ON fhir.fhir_binary TO sus_nexus_fhir_app;

CREATE TABLE fhir.fhir_idx_quantity (
  resource_id text    NOT NULL REFERENCES fhir.fhir_resource (id) ON DELETE CASCADE,
  tenant_id   text    NOT NULL,
  param       text    NOT NULL,
  system      text,
  code        text,
  value       numeric NOT NULL
);
CREATE INDEX fhir_idx_quantity_lookup_idx
  ON fhir.fhir_idx_quantity (tenant_id, param, value);
CREATE INDEX fhir_idx_quantity_resource_idx ON fhir.fhir_idx_quantity (resource_id);

ALTER TABLE fhir.fhir_idx_quantity ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fhir.fhir_idx_quantity
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, DELETE ON fhir.fhir_idx_quantity TO sus_nexus_fhir_app;

-- _history de tipo e de sistema: varredura por data da versão
CREATE INDEX fhir_resource_history_type_updated_idx
  ON fhir.fhir_resource_history (tenant_id, resource_type, last_updated DESC, id, version_id DESC);
CREATE INDEX fhir_resource_history_updated_idx
  ON fhir.fhir_resource_history (tenant_id, last_updated DESC, id, version_id DESC);
