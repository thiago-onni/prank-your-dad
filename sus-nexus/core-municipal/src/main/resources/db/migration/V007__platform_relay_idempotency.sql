-- =====================================================================
-- V007 — platform (segunda leva): marcação de publicação no outbox para o
--        relay de desenvolvimento (produção usa Debezium e ignora a coluna)
--        e expurgo de chaves de idempotência (72 h) sem depender do tenant.
-- =====================================================================

ALTER TABLE platform.event_outbox ADD COLUMN published_at timestamptz;
CREATE INDEX event_outbox_unpublished_idx
  ON platform.event_outbox (created_at) WHERE published_at IS NULL;

-- Expurgo de Idempotency-Key: a tabela tem RLS por tenant; o job roda sem tenant,
-- por isso a função é SECURITY DEFINER (dona: usuário administrador do Flyway).
CREATE OR REPLACE FUNCTION platform.purge_idempotency_keys(max_age interval)
  RETURNS bigint
  LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
  removed bigint;
BEGIN
  DELETE FROM platform.idempotency_key WHERE created_at < now() - max_age;
  GET DIAGNOSTICS removed = ROW_COUNT;
  RETURN removed;
END
$$;
REVOKE ALL ON FUNCTION platform.purge_idempotency_keys(interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.purge_idempotency_keys(interval) TO sus_nexus_app;
