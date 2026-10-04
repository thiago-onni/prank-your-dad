# ADR-0009: Keycloak com padrão BFF; agentes com identidade própria e token exchange

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Tokens no navegador ampliam a superfície de roubo; agentes precisam agir 'em nome de' com escopo mínimo.

## Decisão

Keycloak (OIDC/OAuth2, MFA). Frontends via BFF (Next.js server) com cookie de sessão httpOnly; o navegador nunca recebe o token. Serviços e conectores com client credentials e escopos mínimos. Agentes de IA têm client próprio e usam token exchange; toda ferramenta passa por OPA.

## Consequências

- Menor superfície de ataque no cliente.
- Auditoria distingue usuário, serviço e agente.
