# ADR-0016: Matching do MPI: determinístico em Java + probabilístico Fellegi-Sunter calibrado offline

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Fusões erradas são incidentes clínicos e de privacidade; o modelo precisa ser explicável e auditável.

## Decisão

Ordem determinística: CNS → CPF → vínculo de origem → nome+data+mãe; conflito (mesmo CNS/CPF com data de nascimento diferente) nunca vincula e abre caso. Probabilístico (comparadores Jaro-Winkler/fonética, pesos m/u) gera apenas `provável`/`pendente` para fila de revisão até calibração com amostra rotulada (Splink offline, pesos versionados). Vínculo automático probabilístico só após precisão ≥ 99,5% medida.

## Consequências

- Zero fusão automática incerta na Fase 1.
- Fila de revisão humana é parte do produto.
