-- =====================================================================
-- V012 — reference: atributos adicionais da unidade recebidos nos lotes
--        de upsert dos conectores (CNES): município, competência, extras.
-- =====================================================================
ALTER TABLE reference.health_unit ADD COLUMN city_ibge text;
ALTER TABLE reference.health_unit ADD COLUMN competence text CHECK (competence IS NULL OR competence ~ '^[0-9]{6}$');
ALTER TABLE reference.health_unit ADD COLUMN attributes jsonb;
