CREATE TABLE IF NOT EXISTS integration_message (
  id VARCHAR(40) PRIMARY KEY,
  connector_id VARCHAR(80) NOT NULL,
  source_system VARCHAR(80) NOT NULL,
  source_record_id VARCHAR(255),
  source_record_version VARCHAR(80),
  entity_type VARCHAR(40),
  status VARCHAR(20) NOT NULL,
  raw_ref VARCHAR(1024),
  raw_sha256 VARCHAR(64),
  correlation_id VARCHAR(80),
  received_at TIMESTAMP NOT NULL,
  processed_at TIMESTAMP,
  attempts INT NOT NULL DEFAULT 0,
  error_code VARCHAR(80),
  error_message VARCHAR(2000),
  error_stage VARCHAR(40),
  error_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_integration_message_status ON integration_message (status, received_at);
CREATE INDEX IF NOT EXISTS ix_integration_message_entity ON integration_message (entity_type, status, received_at)
