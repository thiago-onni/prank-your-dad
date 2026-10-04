-- rnds_submission: um registro por event_id (idempotência do conector RNDS). Sem PII.
CREATE TABLE IF NOT EXISTS rnds_submission (
  event_id               VARCHAR(64)  NOT NULL PRIMARY KEY,
  model                  VARCHAR(64)  NOT NULL,
  source_id              VARCHAR(64),
  integration_message_id VARCHAR(64),
  bundle_sha256          VARCHAR(64),
  status                 VARCHAR(16)  NOT NULL,
  http_status            INTEGER,
  protocol               VARCHAR(512),
  outcome_summary        VARCHAR(2000),
  operation_outcome      TEXT,
  attempts               INTEGER      NOT NULL DEFAULT 0,
  created_at             TIMESTAMP    NOT NULL,
  updated_at             TIMESTAMP    NOT NULL,
  submitted_at           TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_rnds_submission_model_created ON rnds_submission (model, created_at);
CREATE INDEX IF NOT EXISTS ix_rnds_submission_source ON rnds_submission (model, source_id);
