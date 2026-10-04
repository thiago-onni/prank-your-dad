# ADR-0004: Kafka (Strimzi, KRaft) com Apicurio Registry e JSON Schema

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Contratos de evento precisam ser legíveis por equipes municipais, validáveis no CI e compatíveis com FHIR JSON.

## Decisão

Apache Kafka em modo KRaft operado pelo Strimzi; Apicurio Registry (open source) com regra de compatibilidade `BACKWARD` (`FULL` para auditoria). Contratos em JSON Schema 2020-12 em `contracts/events`. Serializador valida no produtor.

## Consequências

- Sem dependência de componentes Confluent proprietários.
- JSON é mais verboso que Avro; payloads são mínimos por desenho (KAF-009).
