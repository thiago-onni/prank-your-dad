# RIPD — Relatório de Impacto à Proteção de Dados Pessoais

**Domínio:** Atenção Primária (APS) — cidadão único, agenda e timeline mínima (Fase 1)
**Controlador:** Secretaria Municipal de Saúde de ________ (CNPJ ________)
**Operador(es):** equipe técnica do SUS Nexus / fornecedor ________
**Encarregado (DPO):** ________ (e-mail institucional)
**Versão:** 0.1 (rascunho) · **Data:** ____/____/2026 · **Status:** em elaboração → em revisão → aprovado

> Entregável obrigatório antes do go-live de cada fase (plano, seção 12.1). Este modelo deve ser copiado por domínio: `RIPD-REGULACAO`, `RIPD-HOSPITAL`, `RIPD-PRODUCAO`, `RIPD-IA`.

## 1. Descrição do tratamento

| Item | Descrição |
|---|---|
| Finalidade | Identificação única do cidadão na rede municipal, coordenação do cuidado e visão da agenda, para execução de políticas públicas de saúde e tutela da saúde. |
| Base legal | LGPD art. 7º, III (execução de políticas públicas) e art. 11, II, "b" (tutela da saúde) e "f" (execução de políticas públicas). Validar com a Procuradoria do Município. |
| Natureza dos dados | Pessoais (nome, nome social, nome da mãe, data de nascimento, sexo, endereço, contato, CNS, CPF, identificadores de sistemas) e **sensíveis** (dados de saúde: atendimentos, condições, agendamentos de procedimentos). |
| Titulares | Cidadãos do município atendidos na rede SUS; profissionais de saúde (dados funcionais: CNS, CBO, CNES). |
| Origem | e-SUS APS/PEC, agenda UBS, CNES, CADSUS (quando autorizado). |
| Compartilhamento | Internamente, por papel e vínculo assistencial (OPA). Externamente: nenhum nesta fase (RNDS na Fase 4, com RIPD próprio). |
| Retenção | Dados cadastrais: enquanto houver vínculo com a rede + prazo legal; eventos operacionais: conforme política de retenção (platform/`retention-policy.md`); logs de acesso: 5 anos. |
| Transferência internacional | Não permitida. Modelos de IA só na Fase 2+, com processamento no Brasil ou auto-hospedado (RIPD-IA). |

## 2. Mapeamento dado × origem × finalidade × consumidor

| Dado | Origem | Finalidade | Consumidores (papéis) | Classificação |
|---|---|---|---|---|
| Nome, nome social, data nasc., sexo | PEC | identity_management, care_coordination | profissional_aps, acs (mín.), agendador, cadastro_mestre | restricted |
| CNS, CPF | PEC, CADSUS | identity_management | cadastro_mestre; demais veem mascarado | restricted (hash + máscara) |
| Nome da mãe | PEC | identity_management (matching) | cadastro_mestre | restricted |
| Endereço, território, microárea | PEC | care_coordination, scheduling | profissional_aps, acs | restricted |
| Contato | PEC | scheduling | agendador, acs, profissional_aps | restricted |
| Atendimento APS (resumo) | PEC | care_coordination | profissional_aps | restricted / highly_restricted (por condição) |
| Agendamentos | Agenda UBS/PEC | scheduling, care_coordination | agendador, profissional_aps, acs (status mínimo) | internal |
| Logs de acesso | barramento | security_audit | dpo | internal |

## 3. Necessidade e proporcionalidade

- Minimização: apenas atributos necessários ao matching e à coordenação; conteúdo clínico detalhado permanece no PEC (JOR-008, HOS-010).
- Mascaramento por padrão de CPF/CNS; revelação exige finalidade + justificativa + registro.
- Dados de dev/HML exclusivamente sintéticos (SEC-006; `tools/synthetic-data`).
- Eventos Kafka sem identificadores em claro (KAF-009).

## 4. Riscos e medidas

| # | Risco | Prob. | Impacto | Medidas (controle técnico/organizacional) | Risco residual |
|---|---|---|---|---|---|
| 1 | Acesso fora do vínculo assistencial | Média | Alto | OPA (ABAC por equipe/CNES/microárea), access_log, relatórios do DPO, break-glass auditado | Baixo |
| 2 | Fusão indevida de cadastros | Média | Alto | Só determinístico automático; revisão humana; unmerge; evidências | Baixo |
| 3 | Vazamento por log/exportação | Média | Alto | Filtro de PII em logs (testado), exportação controlada/mascarada, SIEM | Baixo |
| 4 | Acesso indevido por operador de integração | Baixa | Alto | Payload sensível por referência, mascaramento no console, segregação de papéis | Baixo |
| 5 | Comprometimento de credenciais | Média | Alto | MFA, BFF sem token no navegador, rotação via OpenBao, clientes técnicos mínimos | Baixo |
| 6 | Reidentificação em BI | Baixa | Médio | Agregação, supressão de células pequenas, pseudonimização na camada gold | Baixo |
| 7 | Indisponibilidade afetando o cuidado | Média | Médio | Fonte de verdade permanece nos sistemas de origem; SLO; DR | Baixo |

## 5. Direitos dos titulares

Canal de atendimento: ________. Fluxos de acesso, correção (devolutiva ao sistema de origem), informação sobre compartilhamento e revisão de decisões automatizadas (nenhuma decisão exclusivamente automatizada nesta fase).

## 6. Incidentes

Plano de resposta: `platform/security/INCIDENT-RESPONSE.md`. Comunicação à ANPD e aos titulares conforme prazos vigentes.

## 7. Parecer do Encarregado

( ) Aprovado ( ) Aprovado com ressalvas ( ) Reprovado
Ressalvas: ________________________________________________

Assinatura: ______________________ Data: ____/____/______
