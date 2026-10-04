-- Camada bronze do lakehouse (Iceberg, catálogo JDBC `iceberg`). Executado por bootstrap-lakehouse.sh.
-- Uma tabela por domínio de evento (sus.<domínio>.*), alimentada pelo Kafka Connect Iceberg sink
-- (kafka-connect/iceberg-sink-bronze.json, roteamento por event_type). Layout = envelope de evento
-- (contracts/events/envelope.schema.json): o sink grava os campos de mesmo nome, e `data` (objeto) é serializado
-- como texto JSON, e os metadados Kafka vêm do SMT KafkaMetadataTransform (_kafka_metadata_*).
-- Partição: tenant.municipality_id (identidade) + day(occurred_at). Idempotente.
CREATE SCHEMA IF NOT EXISTS iceberg.bronze;
CREATE SCHEMA IF NOT EXISTS iceberg.staging;
CREATE SCHEMA IF NOT EXISTS iceberg.intermediate;
CREATE SCHEMA IF NOT EXISTS iceberg.marts;
CREATE SCHEMA IF NOT EXISTS iceberg.marts_aggregated;
CREATE SCHEMA IF NOT EXISTS iceberg.marts_identified;
CREATE SCHEMA IF NOT EXISTS iceberg.reference;

-- {{TABLES}} — o bloco abaixo é repetido por domínio (gerado por bootstrap-lakehouse.sh a partir de BRONZE_DOMAINS).
CREATE TABLE IF NOT EXISTS iceberg.bronze.events___DOMAIN__ (
    event_id varchar,
    event_type varchar,
    event_version varchar,
    occurred_at timestamp(6) with time zone,
    published_at timestamp(6) with time zone,
    tenant row("municipality_id" varchar, "health_secretariat_id" varchar),
    subject row(
        "municipal_citizen_id" varchar,
        "identifiers" array(row("system" varchar, "value_masked" varchar, "value_hash" varchar))
    ),
    "source" row(
        "system" varchar,
        "connector" varchar,
        "source_record_id" varchar,
        "source_record_version" varchar,
        "cnes" varchar
    ),
    data varchar,
    data_ref varchar,
    privacy row("classification" varchar, "purpose" array(varchar)),
    trace row("correlation_id" varchar, "causation_id" varchar, "schema_version" varchar),
    replay boolean,
    _kafka_metadata_topic varchar,
    _kafka_metadata_partition integer,
    _kafka_metadata_offset bigint,
    _kafka_metadata_timestamp timestamp(6) with time zone
)
COMMENT 'Bronze: eventos sus.__DOMAIN__.* (Kafka Connect Iceberg sink). Contém subject (identificadores mascarados/hash): acesso só do pipeline.'
WITH (
    format = 'PARQUET',
    partitioning = ARRAY['"tenant.municipality_id"', 'day(occurred_at)'],
    format_version = 2
);
