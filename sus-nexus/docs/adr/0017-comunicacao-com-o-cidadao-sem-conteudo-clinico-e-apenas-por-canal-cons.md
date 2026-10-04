# ADR-0017: Comunicação com o cidadão sem conteúdo clínico e apenas por canal consentido

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

LGPD e sigilo profissional; canais como SMS/WhatsApp não são seguros para conteúdo clínico.

## Decisão

Mensagens contêm apenas convite/lembrete/orientação de contato. Nunca diagnóstico, resultado ou condição. Preferências e consentimento em `consent`/`communication_preference`; envio só com aprovação quando originado por agente.

## Consequências

- Reduz risco de exposição.
- Resultado clínico sempre entregue por profissional em canal adequado.
