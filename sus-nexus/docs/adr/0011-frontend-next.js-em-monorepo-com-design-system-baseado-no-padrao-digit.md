# ADR-0011: Frontend Next.js em monorepo com design system baseado no Padrão Digital de Governo

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

Nove aplicações compartilham identidade visual, acessibilidade e cliente de API.

## Decisão

Turborepo + pnpm; Next.js 15 App Router + TypeScript strict. Pacotes `design-system` (tokens gov.br DS, Radix, Tailwind), `domain-components`, `api-client` (gerado do OpenAPI), `auth` (BFF). Um app `shell` para aplicações internas (módulos por rota); portal do cidadão como app separado (Fase 5). WCAG 2.1 AA.

## Consequências

- Reuso e coerência; um deploy interno.
- Portal externo isolado por superfície de risco.
