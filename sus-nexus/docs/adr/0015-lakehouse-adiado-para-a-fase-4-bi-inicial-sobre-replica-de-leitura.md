# ADR-0015: Lakehouse adiado para a Fase 4; BI inicial sobre réplica de leitura

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Entregar indicadores cedo sem o custo operacional de Iceberg/Trino/OpenMetadata.

## Decisão

Fases 1–3: réplica de leitura + schema `analytics` com views materializadas + Metabase. Fase 4: MinIO/Iceberg + dbt + Trino + OpenMetadata.

## Consequências

- Valor rápido; migração planejada.
- Views materializadas exigem agendamento de refresh.
