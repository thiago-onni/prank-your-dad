# ADR-0002: PostgreSQL 16 como banco operacional com RLS por tenant

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Dados de domínio, MPI, tarefas, projeções, outbox e auditoria precisam de transações, JSONB, busca textual e isolamento por município.

## Decisão

PostgreSQL 16+ (CloudNativePG em Kubernetes). Um database por serviço (`core`, `fhir`, `temporal`, `keycloak`, `langfuse`) e um schema por módulo. Toda tabela de domínio tem `tenant_id` e Row-Level Security com `current_setting('app.tenant_id')`, definido por transação. Extensões: `pg_trgm`, `unaccent`, `pgcrypto`, `pgvector`.

## Consequências

- Isolamento de tenant garantido no banco, não só na aplicação.
- Testes exigem PostgreSQL real (sem H2).
- `SET LOCAL app.tenant_id` obrigatório em toda transação — falha fecha o acesso (default-deny).
