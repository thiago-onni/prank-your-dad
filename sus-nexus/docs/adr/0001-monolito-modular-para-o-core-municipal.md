# ADR-0001: Monólito modular para o core municipal

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

O core concentra MPI, referência, terminologia, agenda, jornada, regulação, exames, hospital, cuidado, tarefas, produção, consentimento, comunicação, integração e auditoria. Microsserviços por domínio exigiriam transações distribuídas, dezenas de deploys e depuração difícil para uma equipe municipal.

## Decisão

Um único deployable Java 21 + Quarkus (`core-municipal`), com um módulo por bounded context. Cada módulo expõe apenas `api/` (interfaces e DTOs) e possui seu próprio schema PostgreSQL. ArchUnit impede imports fora de `..<módulo>.api..`. Comunicação entre módulos por API pública ou por evento. Extração para serviço separado só quando houver necessidade medida (escala, equipe, isolamento de falha).

## Consequências

- Transações locais e consistência simples.
- Um pipeline de build/deploy; menor custo operacional.
- Exige disciplina de fronteiras (verificada no CI).
- Escala horizontal do processo inteiro, não por módulo.
