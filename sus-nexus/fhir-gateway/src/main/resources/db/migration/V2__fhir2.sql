-- FHIR-2: inbox idempotente do consumidor de projeção (Kafka) + índice de apoio à ordenação por data.
-- O modelo genérico (fhir_resource + tabelas de índice) já atende aos novos tipos de recurso.

CREATE TABLE fhir.projection_inbox (
  event_id     text        NOT NULL,
  tenant_id    text        NOT NULL,
  topic        text        NOT NULL,
  event_type   text        NOT NULL,
  target_type  text,
  target_id    text,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id)
);

CREATE INDEX projection_inbox_tenant_idx
  ON fhir.projection_inbox (tenant_id, processed_at);

ALTER TABLE fhir.projection_inbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fhir.projection_inbox
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

GRANT SELECT, INSERT ON fhir.projection_inbox TO sus_nexus_fhir_app;

-- _sort por parâmetro de data: subconsulta MIN/MAX(low) por recurso e parâmetro
CREATE INDEX fhir_idx_date_resource_param_idx
  ON fhir.fhir_idx_date (resource_id, param, low);
