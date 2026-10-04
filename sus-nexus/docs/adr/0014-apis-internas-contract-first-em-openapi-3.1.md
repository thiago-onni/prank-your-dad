# ADR-0014: APIs internas contract-first em OpenAPI 3.1

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Frontend e backend evoluem em paralelo sem quebra.

## Decisão

`contracts/openapi/core-municipal.yaml` é a fonte única; o core implementa e testa contra ele; o web gera tipos/cliente. Erros RFC 9457; paginação por cursor; `Idempotency-Key`; `If-Match`.

## Consequências

- Mudanças de API passam por PR no `contracts`.
- Validação automática no CI.
