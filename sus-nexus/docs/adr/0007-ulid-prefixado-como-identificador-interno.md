# ADR-0007: ULID prefixado como identificador interno

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

IDs precisam ser únicos, ordenáveis por tempo, seguros para índices e legíveis quanto ao tipo.

## Decisão

ULID (26 chars Crockford base32) com prefixo por tipo: `cit_`, `apt_`, `reg_`, `task_`, `evt_`, `msg_`, `case_` etc. IDs de origem ficam em tabelas `*_source_link` e em `identifier` FHIR, nunca como chave interna.

## Consequências

- Índices B-tree eficientes; sem colisão entre tipos.
- Prefixo facilita depuração e validação por regex.
