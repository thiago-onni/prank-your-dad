# ADR-0008: Temporal para workflows; OPA para política; regras de negócio em tabelas de decisão versionadas

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Três preocupações distintas: *quando/como* executar fluxos longos, *pode?* (autorização) e *qual regra* aplicar (SLA, risco, priorização).

## Decisão

Temporal (workers Java no core) para fluxos duráveis com sinais, timers e versionamento. OPA (Rego v1) para autorização RBAC+ABAC+finalidade, chamada por core, FHIR e agentes. Regras municipais em `rule_set/rule_version` (YAML validado por JSON Schema) com ciclo de aprovação e `effective_from`; toda decisão registra a versão aplicada.

## Consequências

- Protocolos e SLAs mudam sem deploy.
- Reprodutibilidade de decisões.
- Três motores para operar — mitigado por docker-compose e Helm prontos.
