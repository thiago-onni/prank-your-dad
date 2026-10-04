#!/usr/bin/env bash
# Cria bancos e extensões do SUS Nexus no primeiro boot do PostgreSQL (docker-entrypoint-initdb.d).
# Idempotente por construção: só roda quando o volume de dados está vazio.
set -euo pipefail

APP_USER="${SUS_DB_APP_USER:-sus_nexus}"
APP_PASSWORD="${SUS_DB_APP_PASSWORD:-sus_nexus}"

# Bancos de aplicação (owner = usuário de aplicação) e bancos de plataforma (owner = postgres).
APP_DATABASES=(sus_nexus_core sus_nexus_fhir sus_nexus_ai sus_nexus_test sus_nexus_fhir_test)
PLATFORM_DATABASES=(temporal temporal_visibility keycloak langfuse apicurio metabase litellm)

# Extensões por banco. `vector` (pgvector) só é necessária em core (MPI/embedding) e ai.
EXTENSIONS_COMMON=(pg_trgm unaccent pgcrypto "uuid-ossp" pg_stat_statements)
EXTENSIONS_VECTOR=(vector)

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${APP_USER}') THEN
      CREATE ROLE ${APP_USER} LOGIN PASSWORD '${APP_PASSWORD}';
    END IF;
  END
  \$\$;
  -- Papel de replicação para o Debezium (CDC do outbox)
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'debezium') THEN
      CREATE ROLE debezium LOGIN REPLICATION PASSWORD '${APP_PASSWORD}';
    END IF;
  END
  \$\$;
EOSQL

create_db() {
  local db="$1" owner="$2"
  if ! psql -tA --username "$POSTGRES_USER" --dbname postgres -c "SELECT 1 FROM pg_database WHERE datname='${db}'" | grep -q 1; then
    echo ">> criando banco ${db} (owner ${owner})"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
      -c "CREATE DATABASE ${db} OWNER ${owner} ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;"
  fi
}

create_extensions() {
  local db="$1"; shift
  for ext in "$@"; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" \
      -c "CREATE EXTENSION IF NOT EXISTS \"${ext}\";"
  done
}

for db in "${APP_DATABASES[@]}"; do
  create_db "$db" "$APP_USER"
  create_extensions "$db" "${EXTENSIONS_COMMON[@]}"
  create_extensions "$db" "${EXTENSIONS_VECTOR[@]}"
  # Debezium precisa ler o schema platform.event_outbox (criado pelo Flyway do core).
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
    GRANT CONNECT ON DATABASE ${db} TO debezium;
    ALTER DEFAULT PRIVILEGES FOR ROLE ${APP_USER} IN SCHEMA public GRANT SELECT ON TABLES TO debezium;
EOSQL
done

for db in "${PLATFORM_DATABASES[@]}"; do
  create_db "$db" "$POSTGRES_USER"
done
create_extensions langfuse pgcrypto
create_extensions metabase pg_trgm

echo ">> bancos criados: ${APP_DATABASES[*]} ${PLATFORM_DATABASES[*]}"
