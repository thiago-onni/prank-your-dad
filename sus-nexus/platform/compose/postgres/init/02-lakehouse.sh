#!/usr/bin/env bash
# Lakehouse (Fase 4, ADR-015) — executado no primeiro boot do PostgreSQL (docker-entrypoint-initdb.d) e
# reexecutável manualmente (idempotente) em volumes já existentes:
#   docker compose exec postgres bash /docker-entrypoint-initdb.d/02-lakehouse.sh
#
# - iceberg_catalog: banco do catálogo JDBC do Apache Iceberg (Trino + Kafka Connect Iceberg sink), com as
#   tabelas de metadados (schema V1, com suporte a views) — o Trino exige que já existam.
# - analytics_ro: usuário SOMENTE LEITURA da réplica do core para o catálogo Trino `postgresql`
#   (BYPASSRLS: leitura multi-município; o recorte por município é feito no Trino — rules.json).
# - openmetadata_db: banco do OpenMetadata (perfil `catalog`).
set -euo pipefail

PGUSER="${POSTGRES_USER:-postgres}"
APP_USER="${SUS_DB_APP_USER:-sus_nexus}"
ICEBERG_USER="${ICEBERG_CATALOG_USER:-iceberg}"
ICEBERG_PASSWORD="${ICEBERG_CATALOG_PASSWORD:-iceberg}"
ANALYTICS_USER="${ANALYTICS_RO_USER:-analytics_ro}"
ANALYTICS_PASSWORD="${ANALYTICS_RO_PASSWORD:-analytics_ro}"
OM_USER="${OPENMETADATA_DB_USER:-openmetadata}"
OM_PASSWORD="${OPENMETADATA_DB_PASSWORD:-openmetadata}"

psql_db() { psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname "$1"; }

psql_db postgres <<-EOSQL
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${ICEBERG_USER}') THEN
      CREATE ROLE ${ICEBERG_USER} LOGIN PASSWORD '${ICEBERG_PASSWORD}';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${ANALYTICS_USER}') THEN
      CREATE ROLE ${ANALYTICS_USER} LOGIN BYPASSRLS PASSWORD '${ANALYTICS_PASSWORD}';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${OM_USER}') THEN
      CREATE ROLE ${OM_USER} LOGIN PASSWORD '${OM_PASSWORD}';
    END IF;
  END
  \$\$;
  ALTER ROLE ${ANALYTICS_USER} SET default_transaction_read_only = on;
  ALTER ROLE ${ANALYTICS_USER} SET statement_timeout = '15min';
EOSQL

for spec in "iceberg_catalog:${ICEBERG_USER}" "openmetadata_db:${OM_USER}"; do
  db="${spec%%:*}"; owner="${spec##*:}"
  if ! psql -tA --username "$PGUSER" --dbname postgres -c "SELECT 1 FROM pg_database WHERE datname='${db}'" | grep -q 1; then
    echo ">> criando banco ${db} (owner ${owner})"
    psql_db postgres -c "CREATE DATABASE ${db} OWNER ${owner} ENCODING 'UTF8' TEMPLATE template0;"
  fi
done

# Tabelas do catálogo JDBC do Iceberg (org.apache.iceberg.jdbc.JdbcUtil, schema V1).
psql_db iceberg_catalog <<-EOSQL
  CREATE TABLE IF NOT EXISTS iceberg_tables (
    catalog_name VARCHAR(255) NOT NULL,
    table_namespace VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    metadata_location VARCHAR(1000),
    previous_metadata_location VARCHAR(1000),
    iceberg_type VARCHAR(5),
    PRIMARY KEY (catalog_name, table_namespace, table_name)
  );
  CREATE TABLE IF NOT EXISTS iceberg_namespace_properties (
    catalog_name VARCHAR(255) NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    property_key VARCHAR(255),
    property_value VARCHAR(1000),
    PRIMARY KEY (catalog_name, namespace, property_key)
  );
  ALTER TABLE iceberg_tables OWNER TO ${ICEBERG_USER};
  ALTER TABLE iceberg_namespace_properties OWNER TO ${ICEBERG_USER};
EOSQL

# analytics_ro: leitura dos schemas do core (atuais e futuros criados pelo Flyway com o usuário de aplicação).
if psql -tA --username "$PGUSER" --dbname postgres -c "SELECT 1 FROM pg_database WHERE datname='sus_nexus_core'" | grep -q 1; then
  psql_db sus_nexus_core <<-EOSQL
    GRANT CONNECT ON DATABASE sus_nexus_core TO ${ANALYTICS_USER};
    ALTER DEFAULT PRIVILEGES FOR ROLE ${APP_USER} GRANT USAGE ON SCHEMAS TO ${ANALYTICS_USER};
    ALTER DEFAULT PRIVILEGES FOR ROLE ${APP_USER} GRANT SELECT ON TABLES TO ${ANALYTICS_USER};
    DO \$\$
    DECLARE s text;
    BEGIN
      FOR s IN SELECT nspname FROM pg_namespace
               WHERE nspname IN ('reference', 'identity', 'scheduling', 'journey', 'tasks', 'terminology', 'integration')
      LOOP
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO ${ANALYTICS_USER}', s);
        EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO ${ANALYTICS_USER}', s);
      END LOOP;
    END
    \$\$;
EOSQL
fi

echo ">> lakehouse: iceberg_catalog, openmetadata_db, ${ANALYTICS_USER} (somente leitura) prontos"
