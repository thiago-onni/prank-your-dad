# ADR-0005: Transactional outbox via Debezium Outbox Event Router

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

O core não pode gravar no banco sem publicar o evento, nem publicar sem confirmar a transação (dual write).

## Decisão

Toda escrita de domínio insere o envelope completo em `platform.event_outbox` na mesma transação. Debezium (Kafka Connect) com `EventRouter` roteia por `aggregate_type` para `sus.<...>` com chave `aggregate_id`. Consumidores registram `event_inbox(event_id, consumer_group)` antes de processar (idempotência).

## Consequências

- Entrega at-least-once garantida; duplicatas tratadas pelo inbox.
- Dispensa poller próprio.
- Exige Kafka Connect e permissão de replicação lógica no PostgreSQL.
