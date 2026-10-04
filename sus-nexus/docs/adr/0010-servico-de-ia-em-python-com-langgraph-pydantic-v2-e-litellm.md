# ADR-0010: Serviço de IA em Python com LangGraph, Pydantic v2 e LiteLLM

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

O ecossistema de agentes é Python; o core é Java. Trocar de modelo não pode exigir mudança de código.

## Decisão

`ai-service` (FastAPI) separado do core. LangGraph para grafo/estado/checkpoint; Pydantic v2 para saída estruturada obrigatória; LiteLLM como gateway de modelos; Langfuse auto-hospedado para traces. PydanticAI é opcional, não padrão. Ações classificadas em `auto`, `requires_approval`, `forbidden`; kill switch por agente/ferramenta/unidade/tenant.

## Consequências

- Isolamento de falhas e de dependências entre IA e core.
- Agentes só acessam dados por ferramentas (API do core), nunca bancos.
