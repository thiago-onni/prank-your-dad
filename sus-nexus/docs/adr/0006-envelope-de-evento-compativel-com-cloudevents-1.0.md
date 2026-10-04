# ADR-0006: Envelope de evento compatível com CloudEvents 1.0

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Padronizar metadados de evento para ferramentas e para a trilha de causalidade.

## Decisão

Envelope próprio (`contracts/events/envelope.schema.json`) mapeável para CloudEvents: `event_id→id`, `event_type→type`, `source.connector→source`, `occurred_at→time`; atributos `tenant`, `subject`, `privacy`, `trace` (correlation/causation) como extensões. Headers Kafka `ce_*` + `tenant_id`, `correlation_id`.

## Consequências

- Interoperável com ferramentas CloudEvents.
- Campos obrigatórios validados no CI dos contratos.
