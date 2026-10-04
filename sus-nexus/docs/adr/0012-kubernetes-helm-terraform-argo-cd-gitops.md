# ADR-0012: Kubernetes + Helm + Terraform + Argo CD (GitOps)

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Portabilidade on-premises/nuvem (região BR) e ambientes reprodutíveis.

## Decisão

RKE2 (on-prem, perfil CIS) ou Kubernetes gerenciado em região brasileira. Helm charts por componente + umbrella; valores por ambiente; Argo CD app-of-apps com sync waves; promoção por PR; Argo Rollouts (canário) para core e FHIR. Terraform para rede, cluster, storage, DNS e registry.

## Consequências

- Ambientes dev/hml/prod/dr idênticos por declaração.
- Exige capacitação da equipe municipal (runbooks e pareamento).
