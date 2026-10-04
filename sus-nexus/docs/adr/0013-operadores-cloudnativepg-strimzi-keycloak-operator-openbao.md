# ADR-0013: Operadores: CloudNativePG, Strimzi, Keycloak Operator, OpenBao

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Bancos, Kafka, IAM e segredos precisam de backup, failover e upgrades declarativos.

## Decisão

CloudNativePG (PITR para MinIO/S3, réplica DR), Strimzi (Kafka KRaft, TLS, ACLs, MirrorMaker 2), operador oficial do Keycloak, OpenBao (fork open source do Vault) com External Secrets Operator.

## Consequências

- Operação padronizada em Kubernetes.
- 100% open source.
