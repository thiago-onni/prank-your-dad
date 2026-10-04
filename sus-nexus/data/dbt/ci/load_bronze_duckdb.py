#!/usr/bin/env python3
"""Carrega as fixtures JSONL (um arquivo por tópico) no DuckDB como `bronze.events_<domínio>`.

Reproduz exatamente o layout das tabelas Iceberg gravadas pelo Kafka Connect Iceberg sink
(`platform/compose/trino/bronze-ddl.sql`): colunas do envelope, `data` como texto JSON e metadados Kafka
(`_kafka_metadata_*`, adicionados no lakehouse pelo SMT KafkaMetadataTransform).

Uso: python3 load_bronze_duckdb.py [--fixtures ci/fixtures] [--db target/sus_nexus_ci.duckdb]
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path

import duckdb

HERE = Path(__file__).resolve().parent

COLUMNS = {
    "event_id": "VARCHAR",
    "event_type": "VARCHAR",
    "event_version": "VARCHAR",
    "occurred_at": "VARCHAR",
    "published_at": "VARCHAR",
    "tenant": "STRUCT(municipality_id VARCHAR, health_secretariat_id VARCHAR)",
    "subject": "STRUCT(municipal_citizen_id VARCHAR, identifiers STRUCT(system VARCHAR, value_masked VARCHAR, value_hash VARCHAR)[])",
    "source": "STRUCT(system VARCHAR, connector VARCHAR, source_record_id VARCHAR, source_record_version VARCHAR, cnes VARCHAR)",
    "data": "JSON",
    "data_ref": "VARCHAR",
    "privacy": "STRUCT(classification VARCHAR, purpose VARCHAR[])",
    "trace": "STRUCT(correlation_id VARCHAR, causation_id VARCHAR, schema_version VARCHAR)",
    "replay": "BOOLEAN",
}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default=str(HERE / "fixtures"))
    ap.add_argument("--db", default=str(HERE.parent / "target" / "sus_nexus_ci.duckdb"))
    args = ap.parse_args()

    fixtures = Path(args.fixtures)
    files = sorted(fixtures.glob("sus.*.jsonl"))
    if not files:
        raise SystemExit(f"nenhuma fixture em {fixtures} — rode generate_fixtures.py antes")
    by_domain: dict[str, list[Path]] = defaultdict(list)
    for f in files:
        by_domain[f.name.split(".")[1]].append(f)

    Path(args.db).parent.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect(args.db)
    con.execute("CREATE SCHEMA IF NOT EXISTS bronze")
    for t in con.execute("SELECT table_name FROM information_schema.tables WHERE table_schema = 'bronze'").fetchall():
        con.execute(f'DROP TABLE bronze."{t[0]}"')
    cols = "{" + ", ".join(f"'{k}': '{v}'" for k, v in COLUMNS.items()) + "}"
    for domain, paths in sorted(by_domain.items()):
        file_list = "[" + ", ".join(f"'{p.as_posix()}'" for p in paths) + "]"
        con.execute(f"""
            CREATE TABLE bronze.events_{domain} AS
            SELECT
              event_id, event_type, event_version,
              CAST(occurred_at AS TIMESTAMPTZ) AS occurred_at,
              CAST(published_at AS TIMESTAMPTZ) AS published_at,
              tenant, subject, "source",
              CAST(data AS VARCHAR) AS data,
              data_ref, privacy, trace, COALESCE(replay, false) AS replay,
              regexp_extract(filename, '(sus\\.[^/]+)\\.jsonl$', 1) AS _kafka_metadata_topic,
              0 AS _kafka_metadata_partition,
              CAST(row_number() OVER () AS BIGINT) AS _kafka_metadata_offset,
              CAST(published_at AS TIMESTAMPTZ) AS _kafka_metadata_timestamp
            FROM read_json({file_list}, format = 'newline_delimited', columns = {cols}, filename = true)
        """)
        n = con.execute(f"SELECT count(*) FROM bronze.events_{domain}").fetchone()[0]
        print(f"bronze.events_{domain:12s} {n:6d}")
    con.close()


if __name__ == "__main__":
    main()
