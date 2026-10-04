# SUS Nexus — Plano de Implementação do Barramento Municipal de Saúde Digital

> Plano de execução completo (infraestrutura, backend, frontend, dados, IA, segurança e operação) derivado da especificação funcional e técnica do Barramento Municipal SUS, com FHIR Gateway próprio (sem servidor HAPI FHIR).
>
> Critério de decisão adotado em todo o plano: **caminho mais seguro, robusto e assertivo** — menor risco de entrega, menor superfície de falha, conformidade LGPD desde o primeiro dia e valor assistencial mensurável a cada fase.

---

## Sumário

0. [Sumário executivo](#0-sumário-executivo)
1. [Análise crítica da especificação](#1-análise-crítica-da-especificação)
2. [Decisões de arquitetura (ADRs)](#2-decisões-de-arquitetura-adrs)
3. [Arquitetura-alvo e topologia de implantação](#3-arquitetura-alvo-e-topologia-de-implantação)
4. [Plano de infraestrutura](#4-plano-de-infraestrutura)
5. [Plano de backend](#5-plano-de-backend)
6. [Plano do FHIR Gateway próprio](#6-plano-do-fhir-gateway-próprio)
7. [Plano de conectores e integração](#7-plano-de-conectores-e-integração)
8. [Plano de workflows, regras e automação](#8-plano-de-workflows-regras-e-automação)
9. [Plano de agentes de IA](#9-plano-de-agentes-de-ia)
10. [Plano de dados analíticos e BI](#10-plano-de-dados-analíticos-e-bi)
11. [Plano de frontend e design system](#11-plano-de-frontend-e-design-system)
12. [Segurança, privacidade e LGPD](#12-segurança-privacidade-e-lgpd)
13. [Observabilidade e operação (SRE)](#13-observabilidade-e-operação-sre)
14. [Estratégia de qualidade e testes](#14-estratégia-de-qualidade-e-testes)
15. [Roadmap detalhado por fase e sprint](#15-roadmap-detalhado-por-fase-e-sprint)
16. [Equipe, papéis e governança do projeto](#16-equipe-papéis-e-governança-do-projeto)
17. [Registro de riscos](#17-registro-de-riscos)
18. [Matriz de rastreabilidade de requisitos](#18-matriz-de-rastreabilidade-de-requisitos)
19. [Critérios de entrada em produção (go-live)](#19-critérios-de-entrada-em-produção-go-live)
20. [Próximos passos imediatos (30 dias)](#20-próximos-passos-imediatos-30-dias)

---

## 0. Sumário executivo

### 0.1 O que será construído

Uma plataforma municipal que **integra** (conectores), **normaliza** (modelo canônico), **identifica** (MPI), **correlaciona** (jornada), **coordena** (workflows e tarefas), **governa** (segurança, auditoria, qualidade) e **disponibiliza** (APIs, FHIR R4, BI, aplicações) os dados da rede SUS local — **sem substituir** nenhum sistema legal de registro (PEC, SISREG/e-SUS Regulação, HIS, SIA/SIH).

### 0.2 Estratégia em uma frase

> **Entregar primeiro o "esqueleto andante" (walking skeleton) ponta a ponta — um conector real → evento → MPI → timeline → tarefa → auditoria — em produção controlada, e só então alargar domínios, mantendo um núcleo pequeno, auditável e testado.**

### 0.3 Os 10 pilares de segurança de entrega

| # | Pilar | Como se materializa |
|---|---|---|
| 1 | **Núcleo pequeno primeiro** | Core como *monólito modular* (Quarkus) com fronteiras de domínio rígidas; extração para microsserviços só quando houver necessidade medida |
| 2 | **Stack escalonada** | Componentes "pesados" (Iceberg, Trino, OpenMetadata, NiFi, OpenSearch) entram apenas quando a fase exige — não no dia 1 |
| 3 | **Outbox + idempotência** | Nenhuma escrita sem evento, nenhum evento sem escrita; todo consumidor idempotente (tabela `event_inbox`) |
| 4 | **Dados sintéticos até o go-live** | Dev/HML nunca recebem dado real; gerador sintético brasileiro com CNS/CPF válidos-fictícios |
| 5 | **LGPD por desenho** | RIPD/DPIA antes de cada domínio, OPA com finalidade, mascaramento por campo, auditoria imutável |
| 6 | **FHIR incremental** | Somente recursos homologados no `CapabilityStatement`; biblioteca de referência HL7 encapsulada, sem servidor HAPI |
| 7 | **IA sem autonomia indevida** | Agentes só por ferramentas, ações classificadas (auto / aprovação / proibida), *kill switch* e avaliação contínua |
| 8 | **Tudo como código** | Terraform, Helm, GitOps (Argo CD), políticas OPA, regras, mapeamentos e protocolos versionados |
| 9 | **Gates de fase** | Cada fase só encerra com critérios objetivos (testes, SLO, segurança, aceite do usuário-chave) |
| 10 | **Dependências externas tratadas como risco nº 1** | Acesso a SISREG, CADSUS, RNDS, HIS e LEDI é negociado na Fase 0 com plano B (exportação/arquivo) para cada um |

### 0.4 Linha do tempo resumida

| Fase | Duração | Marco de valor |
|---|---|---|
| **0 — Descoberta e fundação técnica paralela** | 6 semanas | Inventário, matriz LGPD, ADRs, ambientes dev provisionados, PoC PEC |
| **1 — Fundação + MVP APS** | 12 semanas | Cidadão único + timeline APS/agenda + console de integrações em produção controlada (1–3 UBS piloto) |
| **2 — Regulação e exames** | 12 semanas | Cockpit de regulação, ciclo pedido→agendamento→realização, busca ativa de no-show |
| **3 — Hospital, pós-alta e linhas de cuidado** | 14 semanas | ADT hospitalar, pós-alta para APS, linhas de cuidado prioritárias, agentes assistivos |
| **4 — Produção, FHIR/RNDS e BI completo** | 16 semanas | Pré-auditoria BPA/APAC/AIH, FHIR Gateway R4/br-core, lakehouse, sala de situação |
| **5 — Escala e sustentação** | contínuo | Expansão a toda a rede, multi-município, otimização e transferência de conhecimento |

**Total até o escopo completo: ~60 semanas (~14 meses)**, com valor em produção a partir da **semana 18**.

---

## 1. Análise crítica da especificação

A especificação é sólida e coerente com boas práticas de interoperabilidade em saúde. Os pontos abaixo **não invalidam** a especificação; são ajustes que reduzem risco.

### 1.1 Pontos fortes (manter)

- Separação clara entre **fonte de verdade legal** e **barramento** (evita disputa jurídica e de responsabilidade).
- **Modelo canônico municipal** independente de fornecedor — principal ativo de longo prazo.
- **Outbox transacional**, eventos imutáveis, idempotência, DLQ — padrões corretos.
- FHIR como **borda** e não como banco interno — reduz acoplamento e custo.
- Agentes com **autonomia graduada** e humano responsável.
- Escopo explícito do que está **fora** da primeira versão.

### 1.2 Riscos e lacunas identificados, com ajuste recomendado

| # | Ponto da especificação | Risco | Ajuste recomendado |
|---|---|---|---|
| 1 | Stack completa (≈30 componentes) desde o início | Custo operacional altíssimo para uma equipe municipal; tempo de fundação explode | **Stack escalonada por fase** (seção 3.3). MVP com ~12 componentes |
| 2 | Microsserviços implícitos por domínio | Transações distribuídas, sobrecarga de deploy, depuração difícil | **Monólito modular** para o core (módulos = bounded contexts, um schema por módulo), FHIR Gateway, Connector Runtime e AI Service como serviços separados |
| 3 | "Sem HAPI FHIR" + "use biblioteca FHIR R4" | Ambiguidade: as bibliotecas de modelo/validação Java mais maduras estão no repositório `org.hl7.fhir.core` (implementação de referência mantida pela HL7, publicada sob o grupo HAPI) | **ADR-003**: usar `org.hl7.fhir.r4` + validador oficial **apenas como biblioteca**, encapsulada por uma camada anticorrupção; **nenhum** uso do servidor HAPI (JPA server, RestfulServer, interceptors). Alternativa avaliada em spike: geração própria de modelos a partir das StructureDefinitions |
| 4 | LangGraph **e** PydanticAI | Dois frameworks de orquestração de agente sobrepostos | **LangGraph** para grafo/estado/checkpoint + **Pydantic v2** para contratos de saída. PydanticAI fica opcional |
| 5 | NiFi + Camel + Kafka Connect | Três motores de integração sobrepostos | **Camel/Quarkus** como padrão; **Kafka Connect** só para Debezium/outbox e sinks; **NiFi** somente se a Fase 0 provar volume relevante de cargas DBF/legadas que Camel não cubra bem |
| 6 | OpenSearch para logs **e** busca **e** RAG | Ponto único com três perfis de carga distintos | Logs em **Loki**; busca de cidadão primeiro em **PostgreSQL (pg_trgm + unaccent + fonética)**; OpenSearch entra na Fase 3 para timeline/busca textual; RAG com **pgvector** |
| 7 | Acesso a SISREG, CADSUS, RNDS | Dependem de credenciais, termos de adesão, certificado ICP-Brasil e homologação — **fora do controle técnico** | Iniciar trâmites na **semana 1**; cada conector com **plano B** (arquivo/exportação) |
| 8 | MPI probabilístico | Fusões erradas são incidentes clínicos e de privacidade | Nunca fusão automática na Fase 1: **apenas vínculo determinístico automático**; probabilístico gera fila de revisão até calibragem com amostra rotulada |
| 9 | Multi-tenant (município) | Isolamento falho = vazamento entre municípios | `tenant_id` em todas as tabelas + **Row-Level Security** no PostgreSQL + ACL de tópicos Kafka por tenant + realm/grupo Keycloak por tenant |
| 10 | Dados reais em ambientes não produtivos | Violação LGPD | Proibição técnica (políticas de rede, sem rota de dados de produção para HML) + gerador sintético |
| 11 | SLOs 99,9% sem definição de infraestrutura | Promessa sem lastro (datacenter municipal costuma ter janela de manutenção, energia e link únicos) | SLOs **graduais**: 99,5% na Fase 1, 99,9% somente após HA multi-zona comprovada em teste de falha |
| 12 | Ausência de estratégia de dados sintéticos/testes FHIR | Validação fraca | Seção 14: dados sintéticos, testes de contrato, conformidade FHIR, testes de carga e caos |
| 13 | Ausência de RIPD por domínio e base legal | Bloqueio jurídico tardio | RIPD (Relatório de Impacto à Proteção de Dados) como **entregável obrigatório** de cada fase |
| 14 | Portal do cidadão no escopo de aplicações | Superfície externa grande, autenticação de cidadão (gov.br) e alto risco de exposição | Portal do cidadão **adiado para a Fase 5**; até lá, comunicação apenas por canais aprovados e mensagens sem dado clínico |

### 1.3 Premissas assumidas (validar na Fase 0)

1. Município de porte médio (100 mil a 1 milhão de habitantes), 20–150 UBS, 1–5 hospitais/UPAs na rede.
2. Infraestrutura **on-premises** municipal ou nuvem com **região no Brasil**, com possibilidade de híbrido.
3. e-SUS APS/PEC instalado (centralizado ou por unidade).
4. A Secretaria Municipal de Saúde (SMS) é **controladora** dos dados; o fornecedor/equipe técnica é **operadora**.
5. Haverá um **encarregado (DPO)** designado e um **comitê gestor** com poder de decisão sobre prioridades e regras.

---

## 2. Decisões de arquitetura (ADRs)

Cada decisão abaixo deve ser registrada como ADR versionado no repositório (`docs/adr/NNNN-titulo.md`), com contexto, alternativas e consequências.

| ADR | Decisão | Justificativa |
|---|---|---|
| **ADR-001** | **Monólito modular** (Java 21 + Quarkus) para o core municipal | Menos partes móveis, transações locais, refatoração segura; módulos com API interna explícita e testes de arquitetura (ArchUnit) impedindo acoplamento |
| **ADR-002** | **PostgreSQL 16+** como banco operacional, um *database* por serviço e um *schema* por módulo, com **RLS** por `tenant_id` | Maturidade, JSONB, extensões (pg_trgm, unaccent, pgvector, pgcrypto), operadores Kubernetes maduros |
| **ADR-003** | **FHIR Gateway próprio** usando `org.hl7.fhir.r4` (modelos, parser, FHIRPath) e o validador oficial HL7 **como biblioteca**, atrás de interfaces próprias; persistência própria (JSONB + tabelas de índice) | Reutiliza artefatos oficiais (reduz erro de conformidade), mantém controle total de API, persistência e segurança; sem servidor HAPI |
| **ADR-004** | **Kafka (Strimzi, modo KRaft)** + **Apicurio Registry** com **JSON Schema** | JSON Schema é legível por equipes municipais, compatível com FHIR JSON e com validação nativa; Apicurio é 100% open source |
| **ADR-005** | **Outbox via Debezium Outbox Event Router** (Kafka Connect) | Padrão comprovado; evita "dual write"; dispensa poller próprio |
| **ADR-006** | Envelope de evento **compatível com CloudEvents 1.0** (campos da especificação mapeados para atributos e extensões) | Interoperabilidade de ferramentas e padronização |
| **ADR-007** | **ULID** como identificador interno (prefixado por tipo: `cit_`, `reg_`, `evt_`) | Ordenável por tempo, seguro para índices B-tree, sem colisão |
| **ADR-008** | **Temporal** para workflows de longa duração; **OPA** para autorização e políticas; regras de negócio configuráveis em **tabelas de decisão versionadas** (DMN-like em YAML/JSON) | Separação: *quando/como* (Temporal), *pode?* (OPA), *qual regra?* (decisão versionada) |
| **ADR-009** | **Keycloak** com OIDC; frontends via padrão **BFF** (tokens nunca no navegador); serviços com *client credentials* e **token exchange** para agentes | Reduz superfície de roubo de token; agente sempre age "em nome de" com escopo mínimo |
| **ADR-010** | Serviço de IA em **Python 3.12 + FastAPI + LangGraph + Pydantic v2**, LLM via **LiteLLM**, observabilidade **Langfuse** auto-hospedado | Ecossistema de IA é Python; isolamento do core Java; troca de modelo sem mudança de código |
| **ADR-011** | Frontend **Next.js (App Router) + TypeScript** em **monorepo Turborepo**, design system próprio baseado no **Padrão Digital de Governo (gov.br DS)** + componentes acessíveis (Radix) | Coerência com serviços públicos, acessibilidade (eMAG/WCAG 2.1 AA), reuso entre as 9 aplicações |
| **ADR-012** | **Kubernetes** (RKE2 on-prem ou gerenciado em região BR) + **Helm** + **Terraform** + **Argo CD** (GitOps) | Portabilidade on-prem/nuvem; ambientes reprodutíveis |
| **ADR-013** | **CloudNativePG** para PostgreSQL, **Strimzi** para Kafka, operador oficial do **Keycloak**, **OpenBao** para segredos | Operadores com backup, failover e upgrades declarativos |
| **ADR-014** | Contratos de API REST internos em **OpenAPI 3.1**, *contract-first*, com geração de clientes TypeScript | Frontend e backend evoluem sem quebra |
| **ADR-015** | Estratégia de lakehouse **adiada** até a Fase 4; até lá BI em **réplica de leitura + views materializadas + Metabase** | Entrega de indicadores cedo com custo mínimo |
| **ADR-016** | Matching do MPI: **determinístico em Java** + **probabilístico Fellegi-Sunter** (pesos treinados offline com Splink sobre amostra rotulada, aplicados online no core) | Explicável, auditável, calibrável; evita "caixa-preta" |
| **ADR-017** | Mensagens/comunicação com cidadão sempre **sem conteúdo clínico** e somente por canal aprovado e consentido | LGPD e sigilo profissional |

---

## 3. Arquitetura-alvo e topologia de implantação

### 3.1 Visão de componentes (alvo)

```text
                       ┌───────────────────────────────────────────┐
 Usuários (SSO/MFA) ──►│ APISIX (Ingress/API GW) · WAF · mTLS       │◄── Sistemas parceiros (OAuth2 client creds / mTLS)
                       └───────┬───────────────┬───────────────────┘
                               │               │
              ┌────────────────▼───┐   ┌───────▼────────────────┐
              │ Frontends Next.js  │   │ FHIR Gateway (Quarkus) │
              │ (BFF por app)      │   │ /fhir/r4               │
              └────────┬───────────┘   └───────┬────────────────┘
                       │ OpenAPI               │ API interna / eventos
              ┌────────▼──────────────────────────────────────────┐
              │ CORE MUNICIPAL (Quarkus, monólito modular)         │
              │ identity(MPI) · journey · scheduling · regulation  │
              │ exams · hospital · careplan · tasks · production   │
              │ terminology · consent · communication · audit      │
              └───┬──────────────┬───────────────┬────────────────┘
                  │ JDBC         │ outbox(CDC)   │ gRPC/HTTP
        ┌─────────▼───┐   ┌──────▼──────┐   ┌────▼─────────────┐
        │ PostgreSQL  │   │ Kafka +     │   │ Temporal (+ workers│
        │ (CNPG, HA)  │   │ Apicurio    │   │ Java no core)      │
        └─────────────┘   └──┬───────┬──┘   └──────────────────┘
                             │       │
          ┌──────────────────▼─┐   ┌─▼────────────────────────┐
          │ Connector Runtime  │   │ AI Service (Python)       │
          │ Camel/Quarkus      │   │ LangGraph · LiteLLM ·     │
          │ (1 deploy/conector)│   │ Langfuse · pgvector       │
          └─────────┬──────────┘   └──────────────────────────┘
                    │
        PEC/LEDI · SISREG · e-SUS Reg · CNES · CADSUS · HIS · LIS · arquivos
```

Componentes transversais: Keycloak, OPA (sidecar/biblioteca), OpenBao, MinIO, Redis, OpenTelemetry Collector, Prometheus, Grafana, Loki, Tempo, Argo CD, cert-manager, Velero, Kyverno.

### 3.2 Serviços implantáveis

| Serviço | Linguagem | Responsabilidade | Escala |
|---|---|---|---|
| `core-municipal` | Java 21 / Quarkus | Todos os domínios do core + workers Temporal + outbox | Horizontal (stateless), 3+ réplicas |
| `fhir-gateway` | Java 21 / Quarkus | API FHIR R4, validação, projeção FHIR, AuditEvent/Provenance | Horizontal |
| `connector-<nome>` | Java 21 / Quarkus + Camel | Um deployment por conector (isolamento de falha e de credenciais) | Por conector |
| `ai-service` | Python 3.12 / FastAPI | Agentes, ferramentas, avaliações | Horizontal, fila de jobs |
| `bff-<app>` / `web-<app>` | TypeScript / Next.js | Interface e BFF | Horizontal |
| `projection-workers` | Java (no core, módulo separado) | Timeline, read models, indexação | Por consumer group |

### 3.3 Stack escalonada por fase

| Componente | F0/F1 | F2 | F3 | F4 | Observação |
|---|:---:|:---:|:---:|:---:|---|
| Kubernetes, Helm, Terraform, Argo CD | ● | ● | ● | ● | Base |
| PostgreSQL (CNPG) + Redis | ● | ● | ● | ● | |
| Kafka (Strimzi) + Apicurio + Kafka Connect/Debezium (outbox) | ● | ● | ● | ● | |
| Keycloak, OpenBao, OPA, cert-manager | ● | ● | ● | ● | |
| APISIX | ● | ● | ● | ● | |
| OTel + Prometheus + Grafana + Loki + Tempo | ● | ● | ● | ● | |
| MinIO (raw zone) | ● | ● | ● | ● | |
| Temporal | ● | ● | ● | ● | Workflow simples já na F1 (tarefas) |
| Metabase (sobre réplica) | ● | ● | ● | ● | |
| AI Service + LiteLLM + Langfuse + pgvector | | ● | ● | ● | Agente assistivo de regulação na F2 |
| OpenSearch | | | ● | ● | Busca textual de timeline/documentos |
| Iceberg + Trino + dbt + OpenMetadata | | | | ● | Lakehouse |
| Superset (se Metabase não bastar) | | | | ○ | Opcional |
| NiFi | | | | ○ | Somente se justificado |
| Debezium CDC em bases de origem | | | ○ | ● | Somente bases com autorização formal |

### 3.4 Topologia por ambiente

| Ambiente | Finalidade | Dados | Topologia |
|---|---|---|---|
| `local` | Desenvolvimento | Sintéticos | Docker Compose / Kind com perfis reduzidos (Quarkus Dev Services) |
| `dev` | Integração contínua | Sintéticos | Cluster compartilhado, namespaces por equipe |
| `hml` | Homologação, testes de carga, aceite | Sintéticos + mascarados (nunca reais) | Espelho reduzido de produção |
| `prod` | Operação | Reais | HA: 3 nós de controle, ≥ 3 nós de trabalho por zona, 2 zonas (salas/datacenters) quando disponível |
| `dr` | Recuperação de desastre | Backups/réplica | Site secundário (outro prédio/nuvem BR) com RPO ≤ 15 min, RTO ≤ 4 h |

### 3.5 Dimensionamento inicial de produção (referência, município médio)

| Recurso | Fase 1 | Fase 4 |
|---|---|---|
| Nós de trabalho | 6 × (8 vCPU, 32 GB) | 10–14 × (16 vCPU, 64 GB) |
| PostgreSQL core | 1 primário + 2 réplicas, 8 vCPU/32 GB, 500 GB NVMe | 16 vCPU/64 GB, 2 TB |
| Kafka | 3 brokers KRaft, 4 vCPU/16 GB, 500 GB cada | 3–5 brokers, 1–2 TB cada |
| MinIO | 4 nós, erasure coding, 4 TB úteis | 12–20 TB úteis |
| Observabilidade | 3 nós dedicados | 3–5 nós |

> Revisar após a Fase 0 com volumetria real: nº de cidadãos, atendimentos/dia, agendamentos/dia, mensagens HL7/dia, registros de produção/mês.

---

## 4. Plano de infraestrutura

### 4.1 Estrutura de repositórios

```text
sus-nexus-infra/            # Terraform (rede, VMs/cluster, DNS, storage, backups)
sus-nexus-platform/         # Helm charts + valores por ambiente + Argo CD apps (GitOps)
sus-nexus-core/             # Monólito modular Java (core + workers + projeções)
sus-nexus-fhir/             # FHIR Gateway + perfis + testes de conformidade
sus-nexus-connectors/       # Connector SDK + conectores (multi-módulo Maven)
sus-nexus-contracts/        # JSON Schemas de eventos, OpenAPI, perfis FHIR, mapeamentos
sus-nexus-ai/               # AI Service Python + avaliações + prompts versionados
sus-nexus-web/              # Monorepo Turborepo: design system + 9 apps
sus-nexus-policies/         # Políticas OPA (Rego) + testes
sus-nexus-data/             # dbt, modelos analíticos, qualidade (Fase 4)
```

> Alternativa equivalente: monorepo único com CODEOWNERS por pasta. O essencial é **contracts** ser fonte única versionada consumida por todos.

### 4.2 Camadas de infraestrutura e entregas

#### 4.2.1 Rede e borda
- Segmentação: VLAN/sub-redes separadas para **borda (DMZ)**, **aplicação**, **dados** e **gestão**.
- Conectividade com unidades de saúde: VPN site-to-site ou rede municipal existente; conectores que precisam ficar **dentro** da unidade (ex.: PEC local) rodam como **agente de borda** (container leve com saída apenas para o barramento, mTLS).
- **APISIX** como ingress e API Gateway: OIDC, rate limiting por cliente, mTLS para parceiros, logs de acesso sem PII.
- WAF (Coraza/ModSecurity via plugin) nas rotas expostas.
- DNS interno, NTP sincronizado (crítico para ordenação de eventos e auditoria).

#### 4.2.2 Cluster Kubernetes
- Distribuição: **RKE2** (on-prem, perfil CIS) ou Kubernetes gerenciado em região brasileira.
- CNI com NetworkPolicy (**Cilium**); política *default-deny* por namespace.
- **Kyverno**: imagens assinadas (cosign), proibição de `privileged`, `runAsNonRoot`, limites de recurso obrigatórios.
- Namespaces: `platform-*` (operadores), `data-*`, `core`, `fhir`, `connectors`, `ai`, `web`, `observability`, `security`.
- **cert-manager** com CA interna (mTLS entre serviços) e ACME/ICP para borda.
- Storage: CSI com classes `fast-nvme` (bancos, Kafka) e `standard`.

#### 4.2.3 Plataforma de dados
- **CloudNativePG**: clusters separados `core-db`, `fhir-db`, `temporal-db`, `keycloak-db`, `langfuse-db`; backups contínuos (WAL) para MinIO + cópia off-site; PITR testado mensalmente.
- **Strimzi Kafka (KRaft)**: 3 brokers, `min.insync.replicas=2`, `acks=all`, replicação 3, TLS + SCRAM/mTLS, ACLs por serviço e por tenant, Cruise Control para rebalanceamento.
- **Apicurio Registry** com regra de compatibilidade `BACKWARD` (ou `FULL` para eventos críticos).
- **Kafka Connect** (Strimzi) com Debezium PostgreSQL connector + Outbox Event Router.
- **Redis** (Sentinel ou operador) — somente cache/locks/rate-limit; nunca fonte de verdade.
- **MinIO**: buckets `raw-zone` (mensagens brutas, criptografado, retenção por política), `documents`, `exports` (expira), `backups`, `lakehouse` (F4). Object Lock (WORM) para `audit-archive`.

#### 4.2.4 Segurança de plataforma
- **Keycloak**: realm por ambiente; um *organization*/grupo por município (tenant); MFA (TOTP/WebAuthn) obrigatório; federação com AD/LDAP municipal se houver; integração futura com **gov.br** para cidadão (F5).
- **OpenBao** (fork open source do Vault): segredos dinâmicos de banco, PKI interna, transit engine para criptografia de campo (CPF/CNS em repouso quando necessário), External Secrets Operator para injeção no cluster.
- Certificado **ICP-Brasil (e-CNPJ)** da SMS armazenado no OpenBao/HSM para RNDS e serviços DATASUS que o exijam.

#### 4.2.5 Entrega contínua
- **CI** (GitLab CI ou GitHub Actions, conforme padrão municipal): build, testes, SAST (Semgrep), SCA (Trivy/OSV), análise de IaC (Checkov), SBOM (Syft), assinatura de imagem (cosign), testes de contrato.
- Registro de imagens: **Harbor** (com scanner e replicação para DR).
- **Argo CD** com *app-of-apps*; promoção dev → hml → prod por PR no repositório `platform` (aprovação de 2 pessoas para prod).
- Migrações de banco com **Flyway**, executadas como Job antes do rollout; padrão *expand/contract* (sem migração destrutiva no mesmo release).
- Deploy progressivo com **Argo Rollouts** (canário) para `core` e `fhir-gateway`.

#### 4.2.6 Backup, DR e continuidade
| Ativo | Estratégia | RPO | RTO |
|---|---|---|---|
| PostgreSQL | WAL contínuo + base backup diário + réplica no site DR | ≤ 5 min | ≤ 1 h |
| Kafka | MirrorMaker 2 para DR (tópicos críticos) + replay a partir do outbox/raw zone | ≤ 15 min | ≤ 4 h |
| MinIO | Replicação de bucket para DR | ≤ 15 min | ≤ 4 h |
| Configuração do cluster | GitOps (reconstrução declarativa) + Velero | — | ≤ 4 h |
| Segredos | Snapshot OpenBao criptografado + chaves de recuperação com custódia dividida (Shamir) | ≤ 24 h | ≤ 2 h |

Teste de DR **semestral** obrigatório com relatório.

### 4.3 Entregáveis de infraestrutura por fase

| Fase | Entregáveis |
|---|---|
| F0 | Desenho de rede, inventário de capacidade do datacenter, decisão on-prem/nuvem/híbrido, cluster `dev` provisionado, CI básico |
| F1 | Clusters `hml` e `prod`, todos os operadores da coluna F1, backup/PITR testado, Argo CD, Keycloak com MFA, OpenBao, observabilidade completa, hardening CIS, pentest de plataforma |
| F2 | AI stack (LiteLLM, Langfuse), escala Kafka, primeiro teste de DR |
| F3 | OpenSearch, conectores de borda em hospitais, MirrorMaker para DR |
| F4 | MinIO lakehouse, Trino, OpenMetadata, certificados RNDS, segundo teste de DR |

---

## 5. Plano de backend

### 5.1 Estrutura do monólito modular `core-municipal`

```text
core-municipal/
├── platform/               # cross-cutting: tenant context, auditoria, outbox, inbox, erros, segurança, OTel
├── shared-kernel/          # tipos de valor: Cns, Cpf, Cnes, Cbo, SigtapCode, Cid10, Ciap2, Ulid, Period
├── modules/
│   ├── identity/           # MPI: citizen, identifiers, matching, merge/unmerge, golden record
│   ├── reference/          # organization, health_unit, location, professional, role, care_team, territory
│   ├── terminology/        # SIGTAP, CID-10, CIAP-2, CBO, CNES, mapeamentos, versões por competência
│   ├── scheduling/         # appointment, status history, vagas, lista de espera
│   ├── journey/            # timeline (read model), resumo operacional
│   ├── regulation/         # request, queue, decision, capacity, outcome
│   ├── exams/              # exam order, result, report reference
│   ├── hospital/           # episode, bed movement, discharge, counter-referral
│   ├── careplan/           # care plan, items, care gaps, protocolos (linhas de cuidado)
│   ├── tasks/              # care_task, atribuição, SLA, desfecho
│   ├── production/         # production record, validation issue, batch, submission, outcome
│   ├── consent/            # consent, communication preference
│   ├── communication/      # communication, delivery (sem dado clínico)
│   ├── integration/        # registry de conectores, integration_message, erro, reconciliação, DLQ
│   ├── dataquality/        # regras e issues de qualidade
│   └── audit/              # audit_log, access_log, provenance_record
└── app/                    # bootstrap Quarkus, configuração, workers Temporal, consumidores Kafka
```

**Regras de módulo (verificadas por ArchUnit no CI):**
1. Módulo só acessa outro por sua **API pública** (`modules/x/api`) ou por **evento**.
2. Cada módulo tem **seu schema** no PostgreSQL; proibido `JOIN` entre schemas de módulos (exceto views de leitura explicitamente declaradas no módulo `journey`).
3. Toda escrita de domínio grava `event_outbox` na mesma transação.
4. Todo consumidor registra `event_inbox(event_id, consumer)` antes de processar (idempotência).

### 5.2 Padrões técnicos obrigatórios

| Tema | Padrão |
|---|---|
| Multi-tenant | `TenantContext` resolvido do token (claim `municipality_id`) ou do evento; `SET app.tenant_id` por transação; **RLS** em todas as tabelas com `tenant_id` |
| IDs | ULID prefixado; IDs de origem sempre em `*_source_link` (sistema, id, versão) |
| Tempo | Sempre `timestamptz`; distinguir `occurred_at` (fato) e `recorded_at` (registro); fuso `America/Sao_Paulo` só na apresentação |
| Histórico | Tabelas `*_history` (append-only) para status; demografia com **bitemporalidade** (`valid_from/valid_to`, `recorded_from/recorded_to`) |
| Proveniência | Todo atributo de cidadão guarda `source_system`, `source_record_id`, `received_at`, `confidence` |
| Concorrência | *Optimistic locking* (`version`) + `If-Match` nas APIs |
| Erros | RFC 9457 (Problem Details) nas APIs REST internas; `OperationOutcome` no FHIR |
| Idempotência de API | Header `Idempotency-Key` em POSTs de sistemas externos (armazenado 72 h) |
| Paginação | Cursor (keyset) |
| Validação | Bean Validation + validadores de domínio (CNS, CPF, CNES, competência) |
| Observabilidade | OTel automático (Quarkus) + spans de domínio + `correlation_id` propagado em HTTP, Kafka headers e Temporal |
| Logs | JSON estruturado, **sem PII** (filtro de log com mascaramento de CPF/CNS/nome) |
| Configuração | Regras municipais em tabelas versionadas (`rule_set`, `rule_version`, `effective_from`) com aprovação |

### 5.3 Modelo de dados — complementos à especificação

Além das entidades listadas na especificação, incluir:

```text
tenant, tenant_setting
rule_set, rule_version, rule_approval            # configuração antes de código
protocol, protocol_version, protocol_step         # linhas de cuidado configuráveis
sla_policy                                        # SLAs por tipo de tarefa/fila
mapping_set, mapping_version, code_mapping        # mapeamentos origem→canônico versionados
citizen_match_candidate, citizen_match_evidence   # MPI explicável
citizen_golden_record_attribute                   # sobrevivência por atributo
purpose_of_use                                    # finalidades (LGPD) referenciadas por OPA
break_glass_session                               # acesso excepcional
agent, agent_version, agent_tool, agent_run, agent_action, agent_approval  # IA
feature_flag (ou Unleash)                         # kill switch
```

Exemplo de DDL base (padrão para todas as tabelas de domínio):

```sql
CREATE TABLE identity.citizen (
  id                  text PRIMARY KEY,               -- cit_<ULID>
  tenant_id           text NOT NULL,
  status              text NOT NULL CHECK (status IN ('active','merged','inactive')),
  merged_into_id      text NULL REFERENCES identity.citizen(id),
  registration_state  text NOT NULL CHECK (registration_state IN
                       ('validated','divergent','incomplete','duplicate','pending')),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  version             bigint NOT NULL DEFAULT 0
);
ALTER TABLE identity.citizen ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON identity.citizen
  USING (tenant_id = current_setting('app.tenant_id'));

CREATE TABLE identity.citizen_identifier (
  id            text PRIMARY KEY,
  tenant_id     text NOT NULL,
  citizen_id    text NOT NULL REFERENCES identity.citizen(id),
  system        text NOT NULL,   -- CNS, CPF, PEC, SISREG, ESUS_REG, HIS:<cnes>, AIH, APAC...
  value_hash    bytea NOT NULL,  -- HMAC para busca sem expor
  value_enc     bytea NOT NULL,  -- criptografado (OpenBao transit) quando política exigir
  value_masked  text  NOT NULL,  -- ex.: ***.***.***-12
  status        text NOT NULL,   -- active, deprecated, invalid
  source_system text NOT NULL,
  valid_from    timestamptz NOT NULL,
  valid_to      timestamptz NULL,
  UNIQUE (tenant_id, system, value_hash) -- MPI-012: impede recriação
);
```

> Decisão de criptografia de campo (CPF/CNS): avaliar na Fase 0 o custo de busca; padrão recomendado é **HMAC para busca + valor criptografado** apenas para identificadores de alto risco, mantendo criptografia de disco para o restante.

### 5.4 Módulo MPI (identity) — desenho detalhado

**Pipeline de resolução de identidade (executado a cada evento de cidadão vindo de conector):**

```text
1. Normalizar   → maiúsculas, remoção de acentos, abreviações (JR, FILHO, NETO), espaços,
                  nome social separado, telefone E.164, CEP, endereço (logradouro/número)
2. Validar      → CNS (algoritmo de dígito, definitivo/provisório), CPF (DV), data plausível
3. Determinístico (ordem):
     a. CNS válido idêntico ................................. match confirmado
     b. CPF válido idêntico ................................. match confirmado
     c. ID de origem já vinculado (source_link) ............. match confirmado
     d. nome normalizado + data nasc. + nome da mãe idênticos  match confirmado
   Conflito (ex.: mesmo CNS com data de nascimento diferente) → NUNCA vincula; abre CitizenMergeCase
4. Probabilístico (somente se 3 não resolveu):
     blocking: (data nasc.), (soundex/fonética BR do 1º+último nome, ano), (CEP+ano)
     comparadores: Jaro-Winkler nome, fonética BR, nome da mãe, sexo, telefone, endereço, território
     score Fellegi-Sunter → limiares configuráveis por tenant:
        ≥ T_alto  → "provável"  → fila de revisão (Fase 1: NÃO vincula automaticamente)
        T_baixo..T_alto → "pendente" → fila de revisão
        < T_baixo → novo cidadão
5. Persistir    → vínculo, evidências (citizen_match_evidence), proveniência
6. Publicar     → sus.identity.citizen.v1 (created/linked/updated) via outbox
```

**Merge/unmerge (MPI-007):** merge **não apaga** — marca `status=merged`, `merged_into_id`, e mantém todos os vínculos; projeções reagem ao evento `sus.identity.merge.v1`. Unmerge restaura a partir do histórico. Ambos exigem papel específico, justificativa e geram `audit_log` + evento.

**Calibração:** amostra de 2.000–5.000 pares rotulados por equipe de cadastro (com dado real, em ambiente de produção controlado, sob RIPD), treino de pesos offline (Splink), publicação de pesos versionados (`rule_version`). Métricas-alvo para liberar vínculo automático probabilístico (Fase 3+): **precisão ≥ 99,5%** na faixa automática.

### 5.5 Módulo jornada (journey)

- **Read model** `journey.timeline_event` alimentado por consumidores de todos os tópicos de domínio.
- Campos: `citizen_id`, `domain`, `event_type`, `occurred_at`, `recorded_at`, `source_system`, `cnes`, `professional_ref`, `status`, `confidence`, `correlation_chain` (IDs de causa→efeito), `sensitivity` (classificação), `summary` (texto mínimo, sem conteúdo clínico detalhado), `detail_ref` (ponteiro para o domínio).
- Relação causa-efeito (JOR-003): grafo leve por `causation_id` + vínculos de domínio (pedido → regulação → agendamento → realização → retorno), exposto como "cadeias" na UI.
- Filtragem por perfil (JOR-006) aplicada **no servidor** via OPA (filtro de consulta parcial / *partial evaluation*), nunca no frontend.
- Toda leitura gera `access_log` (JOR-007) com finalidade declarada.

### 5.6 APIs internas (OpenAPI) — principais grupos

```text
/api/v1/citizens                 busca, detalhe, identificadores, situação cadastral
/api/v1/citizens/{id}/timeline   timeline filtrável (cursor)
/api/v1/citizens/{id}/summary    resumo operacional (JOR-008)
/api/v1/mpi/cases                fila de revisão, merge, unmerge
/api/v1/appointments             agenda consolidada, vagas ociosas, duplicidades
/api/v1/regulation/requests      fila, pendências, SLA, desfechos
/api/v1/exams/orders             ciclo do exame
/api/v1/hospital/episodes        internações, altas, reinternações
/api/v1/careplans                planos, lacunas, protocolos
/api/v1/tasks                    tarefas, atribuição, desfecho, SLA
/api/v1/production               registros, críticas, lotes, conciliação
/api/v1/integration              conectores, mensagens, erros, reprocessamento, reconciliação
/api/v1/admin                    tenants, regras, protocolos, SLAs, mapeamentos, flags
/api/v1/audit                    trilhas (DPO), relatórios de acesso
/api/v1/agents                   execuções, aprovações, avaliações (cockpit de agentes)
```

### 5.7 Kafka — padrões de implementação

| Item | Padrão |
|---|---|
| Nome de tópico | `sus.<domínio>.<entidade>.v<N>` (especificação) + `.retry.<n>` e `sus.dlq.v1` |
| Chave | `municipal_citizen_id` (ordem por cidadão, KAF-003); eventos sem cidadão usam ID da entidade |
| Partições | 12 por tópico de domínio (iniciais), 3 para baixo volume |
| Retenção | Domínio: 30 dias (fonte de verdade é o PostgreSQL + raw zone); auditoria: 90 dias + arquivo WORM; DLQ: 30 dias |
| Payload | Mínimo (KAF-009): IDs, códigos, status; dado sensível completo por referência (`data_ref` → MinIO/API com autorização) |
| Retry | Retry topics com *backoff* (1 min, 10 min, 1 h) → DLQ com `reason`, `attempts`, `consumer`, `owner` |
| Reprocessamento | Replay por consumer group isolado; efeitos externos protegidos por idempotência + flag `replay=true` que suprime notificações/ações externas (KAF-012) |
| Compatibilidade | Validação de schema no produtor (serializador Apicurio) + teste de compatibilidade no CI do repositório `contracts` |
| Isolamento por tenant (KAF-010) | Fase 1: tenant no envelope + ACL por serviço; multi-município real: prefixo de tópico por tenant (`t<ibge>.sus...`) quando houver requisito de isolamento físico |

### 5.8 Sequência de implementação do backend (ordem segura)

1. `platform` + `shared-kernel` (tenant, auditoria, outbox/inbox, segurança, erros, OTel).
2. `reference` + `terminology` (CNES, CBO, SIGTAP, CID, CIAP-2) — **dados de referência primeiro**, pois tudo depende deles.
3. `identity` (MPI determinístico) + fila de revisão.
4. `integration` (registry de conectores, mensagens, erros, reprocessamento).
5. `scheduling` + `journey` (timeline mínima).
6. `tasks` (com workflow Temporal simples de SLA).
7. `regulation` + `exams` (F2).
8. `hospital` + `careplan` + `consent` + `communication` (F3).
9. `production` + `dataquality` avançado (F4).

---

## 6. Plano do FHIR Gateway próprio

### 6.1 Arquitetura interna

```text
HTTP (JAX-RS / Quarkus REST)
  │  content negotiation (application/fhir+json; xml opcional F4)
  ▼
Interaction Router ── read | vread | create | update | patch(F4) | search | history | $operations
  │
  ├─ AuthN/AuthZ: token Keycloak → SMART-like scopes (patient/*.read, user/*.read, system/*.read)
  │               + OPA (recurso, campo, unidade, equipe, finalidade)
  ├─ Validation Pipeline (create/update/$validate):
  │     1. Parse (org.hl7.fhir.r4 JsonParser, modo estrito)
  │     2. Estrutural + cardinalidade (StructureDefinition base)
  │     3. Perfil br-core/RNDS (meta.profile) — validador oficial como biblioteca, pacotes NPM pré-carregados
  │     4. Terminologia (ValueSets locais + serviço de terminologia próprio)
  │     5. Regras municipais (FHIRPath invariantes próprias)
  ├─ Mapping Layer (bidirecional): modelo canônico municipal ⇄ recursos FHIR (mapeadores versionados)
  ├─ Persistence:
  │     fhir_resource(id, type, version_id, last_updated, tenant_id, profile, content jsonb, deleted)
  │     fhir_resource_history (append-only)
  │     fhir_search_<param> (tabelas de índice: token, string, date, reference, quantity)
  ├─ Masking/Redaction: aplicada após leitura, antes da serialização (por política)
  └─ Side effects: AuditEvent (toda interação), Provenance (derivados de integração), evento Kafka
```

### 6.2 Origem dos dados FHIR: projeção vs. escrita direta

| Modo | Uso | Regra |
|---|---|---|
| **Projeção (padrão)** | Recursos derivados do core (Patient, Encounter, Appointment, ServiceRequest, Task...) | Consumidor Kafka transforma evento canônico → recurso FHIR → grava no `fhir-db` com `Provenance` |
| **Escrita direta (controlada)** | Sistemas parceiros que enviam FHIR (ex.: HIS moderno) | `POST`/`PUT` passa pela validação, é **convertido para o canônico** e entra no core pela mesma porta dos conectores (o core continua sendo o dono). O FHIR nunca é fonte paralela de verdade |

Isso garante uma única fonte de verdade operacional (core) e o FHIR como **borda**.

### 6.3 Ordem de implementação FHIR

| Etapa | Fase | Conteúdo | Critério de pronto |
|---|---|---|---|
| FHIR-0 | F1 (spike, 2 sem.) | ADR-003 confirmado: biblioteca, carga de pacotes br-core, desempenho do validador | Validação de 1.000 Patients br-core < 50 ms p95 por recurso |
| FHIR-1 | F2 | `metadata`, `Patient`, `Organization`, `Location`, `Practitioner`, `PractitionerRole` (read/search) por projeção | Testes de conformidade internos + CapabilityStatement gerado do código |
| FHIR-2 | F3 | `Encounter`, `Appointment`, `ServiceRequest`, `Task`, `Condition`, `CarePlan`, `Provenance`, `AuditEvent` | Busca por paciente/data/status; `_include` mínimo |
| FHIR-3 | F4 | `Observation`, `DiagnosticReport`, `DocumentReference`, `$everything`, `$validate`, Bundles `transaction`/`batch`, escrita direta, `history`, ETag/If-Match | Teste com cliente externo real + validação RNDS em homologação |
| FHIR-4 | F4/F5 | Prioridade 2 e 3 (CareTeam, RelatedPerson, Procedure, MedicationRequest, ImagingStudy, Coverage, EpisodeOfCare, CommunicationRequest, RiskAssessment), `patch`, XML | Por demanda real de consumidor |

### 6.4 Regras de conformidade

- **CapabilityStatement gerado automaticamente** a partir do registro de interações implementadas (impossível anunciar o que não existe).
- `meta.profile` obrigatório para recursos com perfil implementado; rejeição de perfis desconhecidos em escrita.
- Pacotes de IG (br-core, RNDS) **versionados e fixados** no build; atualização via PR com regressão completa.
- Conjunto de testes: exemplos oficiais dos IGs como *fixtures* + testes negativos.
- `$everything` com limite de páginas, filtragem por política e registro de finalidade.
- Paginação por cursor opaco assinado (evita adulteração de parâmetros).

### 6.5 Integração RNDS (Fase 4)

1. Adesão formal do município/estabelecimentos e credenciamento junto ao DATASUS (iniciado na F0).
2. Certificado digital ICP-Brasil configurado (OpenBao/HSM), autenticação conforme especificação vigente da RNDS.
3. Conector `connector-rnds` (envio de modelos de informação aplicáveis ao escopo habilitado — ex.: resultados de exames, sumário de alta, registros de atendimento — **conforme a habilitação efetiva do município**).
4. Ambiente de homologação RNDS antes de produção; reconciliação de envios (aceito/rejeitado) com `OperationOutcome` armazenado.

> Os contratos e modelos de informação da RNDS evoluem; a Fase 0 deve levantar a versão vigente da documentação oficial e os modelos que o município está apto a enviar/consumir.

---

## 7. Plano de conectores e integração

### 7.1 Connector SDK (Java/Quarkus/Camel)

```java
public interface Connector {
    ConnectorDescriptor descriptor();          // metadados obrigatórios (seção 5.6 da spec)
    AuthResult authenticate();
    HealthStatus healthCheck();
    Capabilities discoverCapabilities();
    IngestResult ingest(IngestRequest req);     // pull/push/arquivo
    CanonicalBatch transform(RawMessage raw);   // via mapping_version
    ValidationReport validate(CanonicalBatch b);
    PublishResult publish(CanonicalBatch b);    // SEMPRE via API de entrada do core (não direto no Kafka de domínio)
    ReconciliationReport reconcile(Period p);
    RetryDecision retry(FailedMessage m);
    ErrorDetails getErrorDetails(String messageId);
    void emitMetrics(MetricsSink sink);
}
```

**Decisão de segurança:** conectores **não escrevem diretamente** nos tópicos de domínio nem no banco do core. Eles publicam no tópico de entrada `sus.ingest.<fonte>.v1` (ou API de ingestão) e o core valida, resolve identidade, persiste e emite o evento canônico via outbox. Isso garante uma única porta de escrita, regras únicas e auditoria única.

**Fluxo padrão de cada mensagem:**

```text
fonte → [conector] grava bruto no MinIO (raw-zone, hash SHA-256) → integration_message (status=received)
     → transforma (mapping_version) → valida → publica em sus.ingest.* (status=published)
     → [core] consome, idempotência por (source_system, source_record_id, source_version)
     → persiste + outbox → sus.<domínio>.* (status=processed)
     → erro em qualquer etapa → integration_error + retry → DLQ + tarefa para operador de integração
```

**Kit do SDK:** arquétipo Maven, testes de contrato (*golden files* entrada→saída canônica), simulador de fonte, gerador de massa sintética, dashboard Grafana padrão, alertas padrão, runbook-modelo.

### 7.2 Catálogo de conectores — abordagem e plano B

| Conector | Fase | Abordagem principal | Plano B | Pontos de atenção |
|---|---|---|---|---|
| **CNES** | F1 | Web services DATASUS (SOAP) e/ou arquivos oficiais por competência | Carga mensal de arquivo público | Base de referência de unidades, profissionais, equipes, habilitações |
| **SIGTAP / CID / CBO / CIAP-2** | F1 | Arquivos oficiais por competência (SIGTAP) e tabelas oficiais | — | Versionar por competência; terminologia temporal |
| **e-SUS APS/PEC** | F1 | Leitura das fichas/LEDI (Thrift) e/ou acesso autorizado de leitura ao banco do PEC (réplica) | Exportações periódicas | Verificar versão do PEC por unidade; nunca escrever no PEC; preferir réplica para não impactar produção |
| **Agenda UBS** | F1 | Via PEC (agenda) ou sistema municipal de agendamento (API) | Arquivo diário | Eventos de status (confirmação, falta) são essenciais |
| **CNS/CADSUS** | F1/F2 | Web service de consulta autorizado (perfil de acesso concedido ao município) | Validação local de DV + revisão manual | Consulta somente sob demanda e com finalidade; cache com TTL e auditoria |
| **SISREG** | F2 | Interface/exportação disponível ao gestor municipal (depende do acordo com o Ministério/estado) | Exportação de relatórios + carga | **Maior risco do projeto**: confirmar na F0 o meio técnico permitido |
| **e-SUS Regulação** | F2 | APIs/interfaces disponibilizadas | Exportação | Mesmo trâmite de autorização |
| **HIS hospitalar** | F3 | HL7 v2 ADT (A01, A02, A03, A04, A08, A11, A13) via MLLP/TLS; FHIR se disponível | Arquivo/consulta periódica | Conector de borda dentro do hospital; mapeamento por fornecedor (Tasy, MV, AGHUse...) |
| **Laboratório (LIS)** | F3 | HL7 v2 ORM/ORU ou API | Arquivo | Resultado crítico (EXA-007) segue fluxo institucional |
| **Imagem (RIS/PACS)** | F3 | HL7 ORM/ORU + metadados DICOM (sem imagem) | — | Só metadados e link seguro |
| **SIA/BPA/APAC** | F4 | Arquivos de produção (BPA-Mag/APAC) e retornos de processamento | — | Pré-auditoria **antes** da transmissão oficial |
| **SIH/AIH** | F4 | Arquivos SISAIH01/exportações do HIS e retornos | — | Conciliação com episódios hospitalares |
| **RNDS** | F4 | FHIR R4 conforme habilitação | — | Certificado ICP-Brasil |
| **SI-PNI, e-SUS Notifica/SINAN** | F5 | Interfaces vigentes autorizadas | — | Escopo a definir |

### 7.3 Reconciliação (obrigatória por conector)

Job diário (Temporal) que compara contagens/hashes **fonte × barramento** por entidade e período; diferenças viram `integration_reconciliation` + alerta + tarefa. Métrica `integration_reconciliation_gap_total`.

---

## 8. Plano de workflows, regras e automação

### 8.1 Temporal — padrões

- Workers Java no `core-municipal` (filas de tarefa por domínio: `tasks`, `regulation`, `hospital`, `production`).
- Workflows **determinísticos**; toda I/O em *activities* idempotentes.
- Iniciados por consumidores Kafka com `workflowId` derivado do evento de negócio (ex.: `exam-followup:<exam_order_id>`) → impede workflows duplicados em replay.
- Sinais (`signal`) para eventos subsequentes (agendado, realizado, laudo, contato realizado).
- **Versionamento** de workflow (`Workflow.getVersion` / worker versioning) — protocolos mudam.
- Prazos lidos de `sla_policy`/`protocol_version` vigentes **no início** do workflow (registrado para reprodutibilidade).
- Tarefa humana = `care_task` no core + `signal` de conclusão; o workflow nunca "espera a UI".

### 8.2 Workflows a implementar

| Workflow | Fase | Gatilho | Saídas |
|---|---|---|---|
| `TaskSlaWorkflow` | F1 | `task.created` | Lembrete, escalonamento, `workflow_sla_breach_total` |
| `MpiReviewWorkflow` | F1 | caso de merge aberto | Atribuição à fila de cadastro, SLA de revisão |
| `ExamFollowUpWorkflow` (Workflow 1 da spec) | F2 | `exam.order.created` | Tarefas de agendamento, busca ativa, retorno |
| `NoShowRecoveryWorkflow` | F2 | `appointment.no_show` | Tarefa de busca ativa por equipe (AGE-006) |
| `RegulationSlaWorkflow` | F2 | `regulation.request.created` | Alertas de SLA, pendência documental (REG-010) |
| `DischargeFollowUpWorkflow` (Workflow 2) | F3 | `hospital.discharge.completed` | Resolução de equipe, risco por regra, tarefa, escalonamento ACS |
| `CareGapDetectionWorkflow` | F3 | agendado (cron) por linha de cuidado | `care_gap` + lista de busca ativa (CUI-003/004) |
| `ProductionPreAuditWorkflow` (Workflow 3) | F4 | `production.record.created` | Validação, pendência, lote somente com aprovação humana |
| `ConnectorReconciliationWorkflow` | F1 | cron diário | Relatório de reconciliação |
| `CompetenceDeadlineWorkflow` | F4 | calendário de competência | Alertas de prazo (PRO-007) |

### 8.3 Regras configuráveis ("configuração antes de código")

- **Tabelas de decisão versionadas** (YAML validado por JSON Schema) para: prazos de SLA, classificação de risco pós-alta, critérios de lacuna por linha de cuidado, regras de priorização municipal (REG-011), regras de pré-auditoria.
- Ciclo de vida: `rascunho → em revisão → aprovado (com responsável técnico/clínico) → vigente (effective_from) → revogado`.
- Avaliador de regras em Java (motor simples próprio sobre FEEL/expressões restritas, ou **Kogito DMN** se a complexidade justificar).
- Toda decisão registra `rule_version` aplicada → reprodutibilidade e auditoria.
- Teste obrigatório: cada versão de regra precisa de casos de teste anexados antes da aprovação.

---

## 9. Plano de agentes de IA

### 9.1 Arquitetura do AI Service

```text
Gatilho (evento Kafka / pedido do usuário / workflow Temporal)
   ▼
Agent Runner (LangGraph, estado com checkpoint em PostgreSQL)
   ├─ Context Builder: busca dados SÓ via ferramentas → minimização/pseudonimização (AIA-007)
   ├─ LLM call via LiteLLM (modelo por agente, fallback, limite de custo, sem retenção no provedor)
   ├─ Output: Pydantic v2 schema estrito (AIA-003); falha de validação → retry limitado → descarte
   ├─ Action Classifier: auto | requires_approval | forbidden (catálogo por agente/ferramenta, AIA-004)
   ├─ Policy check: OPA com identidade do agente (token exchange Keycloak), AIA-001/AIA-012
   └─ Effects: cria Task/sugestão no core via API pública (nunca banco; AIA-006)
   ▼
Registro: agent_run (prompt versionado, modelo, versão, ferramentas, evidências, saída, decisão, ação)
          + trace Langfuse (AIA-002, AIA-010)
```

### 9.2 Regras de implementação

| Regra | Implementação |
|---|---|
| Ferramentas formais | Cada ferramenta = endpoint do core com escopo OAuth próprio, schema de entrada/saída, classificação de risco e dono |
| Minimização | Agente recebe IDs pseudônimos e atributos mínimos; reidentificação apenas no core, ao materializar a tarefa |
| Ações proibidas | Alterar prioridade clínica, decisão regulatória, transmitir produção, enviar mensagem com conteúdo clínico, fundir cadastros (REG-009, PRO-010) — bloqueadas por OPA, não só por prompt |
| Kill switch (AIA-009) | Feature flag (Unleash/OpenFeature) por agente, ferramenta, unidade e tenant, verificada antes de cada execução e cada ação |
| Avaliação (AIA-008) | Conjunto de testes por agente (casos sintéticos rotulados) rodado no CI a cada mudança de prompt/modelo; métricas de precisão/recall; feedback humano do cockpit alimenta o conjunto |
| Reprodutibilidade (AIA-010) | Prompt, modelo, parâmetros, versões de regras e entradas (referenciadas) guardados por execução |
| Modelo de LLM | Preferência por provedores com processamento/armazenamento no Brasil ou modelo auto-hospedado; contratos sem uso de dados para treino; avaliação jurídica na F0 |
| Prompt injection | Dados de origem tratados como dados (delimitação, sem execução de instruções vindas de texto livre), ferramentas com saída tipada, OWASP LLM Top 10 como checklist |

### 9.3 Sequência de agentes

| Agente | Fase | Autonomia inicial | Métrica de liberação para próximo nível |
|---|---|---|---|
| Regulação (completude/resumo) | F2 | Sugestão + pendência com aprovação | ≥ 90% de concordância do regulador em 300 casos |
| Cadastro (duplicidade) | F2 | Somente sugestão | Precisão ≥ 95% nas sugestões aceitas |
| Busca ativa | F3 | Cria tarefa (auto); comunicação só aprovada | Taxa de tarefa útil ≥ 80% |
| Pós-alta | F3 | Cria tarefa (auto) | Tarefas criadas < 15 min (SLO) e ≥ 85% úteis |
| Protocolo (RAG) | F3 | Orientação assistiva | Avaliação de fidelidade às fontes ≥ 95% |
| Auditoria de produção | F4 | Sugestão + evidência | Concordância ≥ 90% com auditor |
| BI (perguntas) | F4 | Leitura agregada (sem dado individual) | Consultas somente em camada agregada com k-anonimato mínimo |

---

## 10. Plano de dados analíticos e BI

### 10.1 Fases de maturidade analítica

| Fase | Arquitetura | Entregas |
|---|---|---|
| F1–F3 | Réplica de leitura do PostgreSQL + schema `analytics` com views materializadas + **Metabase** | Painéis de integração, cadastro, agenda, regulação, hospital |
| F4 | **Lakehouse**: eventos Kafka → MinIO (Iceberg, via Kafka Connect Iceberg sink) + extrações do core → **dbt** (bronze/silver/gold) → **Trino** → Metabase/Superset; **OpenMetadata** para catálogo/linhagem | Sala de situação, indicadores de produção/financiamento, séries históricas |

### 10.2 Regras de governança analítica

- Camada **gold** pseudonimizada por padrão; acesso a dado individual só em *data marts* operacionais com RBAC/ABAC.
- Supressão de células pequenas (ex.: n < 5) em painéis públicos/gestão.
- Testes de qualidade dbt (unicidade, não nulo, integridade referencial, frescor) com alertas.
- Dicionário de indicadores (fórmula, fonte, periodicidade, dono) publicado no OpenMetadata.
- Indicadores priorizados na Fase 0 com a gestão (seção 4.9 da spec), alinhados aos indicadores de financiamento federal da APS vigentes.

---

## 11. Plano de frontend e design system

### 11.1 Arquitetura

```text
sus-nexus-web/ (Turborepo + pnpm)
├── packages/
│   ├── design-system/      # tokens (gov.br DS adaptado), componentes acessíveis (Radix), Storybook
│   ├── domain-components/  # CitizenHeader, TimelineEvent, ... (15 componentes da spec)
│   ├── api-client/         # gerado do OpenAPI (orval/openapi-typescript) + TanStack Query hooks
│   ├── auth/               # BFF OIDC (sessão httpOnly, refresh no servidor), guarda de rotas
│   ├── i18n/               # pt-BR (estrutura para es/en futuro)
│   └── telemetry/          # OTel web + Web Vitals (sem PII)
└── apps/
    ├── console-integracoes/
    ├── cadastro-mestre/
    ├── timeline-cidadao/
    ├── cockpit-regulacao/
    ├── workbench-cuidado/
    ├── auditoria-producao/
    ├── cockpit-agentes/
    ├── sala-situacao/
    └── portal-cidadao/     # F5
```

> Alternativa a avaliar com a equipe: um **único app Next.js "shell"** com módulos por rota e permissões, em vez de 9 deploys. **Recomendação:** shell único para as aplicações internas (menos deploy, SSO uniforme, navegação contínua entre timeline/tarefas/regulação) + app separado para o portal do cidadão (superfície externa isolada).

### 11.2 Padrões obrigatórios

| Tema | Padrão |
|---|---|
| Autenticação | BFF: Next.js server faz OIDC com Keycloak; navegador só tem cookie de sessão `httpOnly`, `Secure`, `SameSite=strict` |
| Autorização na UI | Somente *usabilidade* (esconder ações); a **autorização real é sempre no backend** |
| Dados sensíveis | Mascarados por padrão (CPF/CNS), revelação sob ação explícita com registro de finalidade ("mostrar CPF" gera `access_log`) |
| Acessibilidade | WCAG 2.1 AA / eMAG: navegação por teclado, contraste, leitores de tela; testes automatizados (axe) no CI |
| Desempenho | Server Components para leitura, *streaming*, virtualização de listas (filas e timelines grandes) |
| Resiliência | Estados explícitos: carregando, vazio, erro, **dado pendente/divergente/não sincronizado** (JOR-005) |
| Segurança web | CSP estrita, sem `dangerouslySetInnerHTML`, cabeçalhos de segurança, proteção CSRF no BFF |
| Uso em campo (ACS) | Layout responsivo para tablet/celular; modo offline **não** no MVP (avaliar F5 com PWA e cache criptografado) |
| Testes | Vitest + Testing Library (unidade), Playwright (E2E com dados sintéticos), regressão visual no Storybook |

### 11.3 Componentes de domínio (da spec) — especificação mínima

| Componente | Comportamento-chave |
|---|---|
| `CitizenHeader` | Nome (social prioritário), idade, identificadores mascarados, equipe/UBS de referência, situação cadastral, alertas de consentimento |
| `IdentityConfidenceBadge` | confirmado / provável / pendente / divergente, com tooltip de evidência |
| `SourceSystemBadge` | Sistema de origem + data de sincronização |
| `DataProvenancePanel` | Origem por atributo, histórico, confiança |
| `TimelineEvent` | Tipo, datas (ocorrência/registro), unidade/CNES, status, cadeia causa-efeito expansível |
| `CareGapCard` | Lacuna, protocolo/versão, prazo, ação sugerida |
| `RegulationQueueCard` | Prioridade, tempo de espera, SLA, pendências, prestador |
| `AppointmentStatusChip` | Estados de agenda padronizados |
| `TaskSLAIndicator` | Tempo restante/estourado, escalonamento |
| `HumanApprovalPanel` | Aprovar/rejeitar com justificativa obrigatória |
| `AgentDecisionTrace` | Entradas (mínimas), ferramentas chamadas, saída, regra/versão, aprovador |
| `FHIRResourceViewer` | JSON/árvore com mascaramento aplicado, perfil e validação |
| `IntegrationHealthBadge` | Saudável/degradado/parado + última mensagem |
| `DataQualityIssueCard` | Regra violada, registro, origem, ação |
| `ConsentStatusIndicator` | Consentimentos e preferências de comunicação |

### 11.4 Ordem de entrega das aplicações

| Fase | Aplicações |
|---|---|
| F1 | Design system v1, Console de Integrações, Cadastro Mestre (busca + fila de revisão), Timeline (APS + agenda) |
| F2 | Cockpit de Regulação, Workbench de Cuidado v1 (tarefas, no-show), Cockpit de Agentes v1 |
| F3 | Workbench completo (pós-alta, linhas de cuidado, busca ativa por microárea), Timeline completa |
| F4 | Auditoria de Produção, Sala de Situação, FHIRResourceViewer |
| F5 | Portal do Cidadão (gov.br, somente agenda/status de pedido/lembretes) |

### 11.5 Pesquisa com usuário

- Entrevistas e observação em campo na F0 (UBS, central de regulação, hospital, auditoria).
- Protótipos navegáveis validados **antes** de cada sprint de UI.
- Teste de usabilidade com 5–8 usuários reais por aplicação antes do go-live.

---

## 12. Segurança, privacidade e LGPD

### 12.1 Governança de privacidade

| Entregável | Momento |
|---|---|
| Mapeamento de dados (dado × origem × finalidade × consumidor × base legal) | F0 |
| Bases legais: tutela da saúde e execução de políticas públicas pela administração (arts. 7º, 11 da LGPD) — validar com jurídico municipal | F0 |
| RIPD por domínio (APS, regulação, hospital, produção, IA) | Antes de cada fase entrar em produção |
| Contratos/termos com operadores (fornecedores, provedor de LLM, nuvem) | F0–F1 |
| Política de retenção e descarte por classe de dado | F1 |
| Plano de resposta a incidentes (incluindo comunicação à ANPD e titulares) | F1 |
| Canal de direitos do titular (acesso, correção) integrado à SMS | F3 |

### 12.2 Autorização (RBAC + ABAC + OPA)

**Entrada da decisão OPA:**
```json
{
  "subject": {"id": "...", "roles": ["profissional_aps"], "tenant": "ibge_X",
              "cnes": ["1234567"], "teams": ["ine_0001"], "microareas": []},
  "action": "read",
  "resource": {"type": "timeline_event", "domain": "hospital", "sensitivity": "restricted",
               "citizen_team": "ine_0001", "cnes": "7654321"},
  "context": {"purpose": "care_coordination", "break_glass": false, "channel": "web"}
}
```

**Regras base (exemplos):**
- Profissional APS: lê cidadãos **vinculados à sua equipe** (vínculo assistencial) + finalidade `care_coordination`.
- ACS: dados mínimos (nome, endereço, contato, tarefas) da **sua microárea**; nunca diagnóstico/laudo.
- Regulador: solicitações e documentos pertinentes das filas sob sua responsabilidade.
- Agendador: identificação, contato, agenda e status operacional mínimo.
- Gestor: somente agregados.
- Agente de IA: somente ferramentas explicitamente concedidas; ações proibidas negadas.
- **Categorias especialmente sensíveis** (saúde mental, HIV/IST, violência, gestação em adolescente, etc.) com marcação `sensitivity=highly_restricted` e regra mais restrita.

Políticas OPA com **testes unitários** (`opa test`) e cobertura obrigatória no CI; *decision logs* enviados ao audit.

### 12.3 Controles técnicos (SEC-001 a SEC-012)

| Requisito | Implementação |
|---|---|
| SEC-001 SSO+MFA | Keycloak, MFA obrigatório (WebAuthn preferencial), sessão com timeout por papel |
| SEC-002 Clientes técnicos | Um client por serviço/conector, *client credentials*, escopos mínimos, rotação automática |
| SEC-003 mTLS | Parceiros externos e conectores de borda; malha interna com mTLS (cert-manager/Linkerd opcional) |
| SEC-004 Criptografia | TLS 1.2+ (preferir 1.3); disco criptografado; MinIO SSE-KMS (OpenBao); campo para identificadores de alto risco |
| SEC-005 Segredos | OpenBao + External Secrets; varredura de segredos no CI (gitleaks) |
| SEC-006 Dados não produtivos | Sintéticos; política de rede bloqueia fluxo prod→hml |
| SEC-007 Logs sem PII | Filtro de mascaramento na biblioteca de log + teste automatizado que falha se detectar CPF/CNS em log |
| SEC-008 Exportações | Fluxo com justificativa, aprovação, marca d'água, expiração do link, auditoria |
| SEC-009 Retenção | Jobs de expurgo/anonimização por política (Temporal), com evidência |
| SEC-010 Incidentes | Runbook, contatos, playbooks SIEM, simulado anual |
| SEC-011 Break-glass | Sessão temporária (ex.: 1 h), justificativa obrigatória, alerta imediato ao DPO/gestor, revisão posterior |
| SEC-012 IA não contorna políticas | Agente com identidade própria no Keycloak, toda ferramenta passa por OPA |

### 12.4 Auditoria imutável

- `audit_log` append-only com **encadeamento de hash** (cada registro contém hash do anterior) + exportação diária para bucket WORM (Object Lock).
- `AuditEvent` FHIR gerado a partir do `audit_log` para consumidores externos autorizados.
- Relatórios do DPO: quem acessou o cidadão X; acessos fora do vínculo; break-glass; exportações.

### 12.5 Segurança de aplicação

- OWASP ASVS nível 2 como requisito de aceite; OWASP API Top 10 para APIs; OWASP LLM Top 10 para IA.
- Pentest externo antes de cada go-live de fase; correção de achados críticos/altos obrigatória.
- Threat modeling (STRIDE) por domínio na F0/início de cada fase.

---

## 13. Observabilidade e operação (SRE)

### 13.1 Instrumentação

- OpenTelemetry em todos os serviços (traces, métricas, logs) → OTel Collector → Tempo/Prometheus/Loki → Grafana.
- `correlation_id` do evento = `trace_id` lógico de negócio, propagado em HTTP, headers Kafka e Temporal.
- Métricas técnicas da spec (seção 5.9) implementadas como **contrato** no SDK de conectores e no core.

### 13.2 Dashboards padrão

1. Saúde da plataforma (cluster, bancos, Kafka, lag, DLQ).
2. Conectores (recebido/processado/falho, latência, reconciliação).
3. MPI (taxa de match, duplicidade, fila de revisão).
4. FHIR (latência, erros de validação, por consumidor).
5. Workflows (abertos, SLA estourado, por tipo).
6. IA (chamadas, negações, aprovação humana, custo, latência).
7. Segurança (logins falhos, break-glass, negações OPA, exportações).

### 13.3 SLOs graduais

| Serviço | F1 | F3 | F4+ |
|---|---|---|---|
| API de cidadão/MPI | 99,5% | 99,7% | 99,9% |
| Agenda e regulação | — | 99,5% | 99,5% |
| Eventos críticos publicados ≤ 60 s | 99% | 99,5% | 99,9% |
| Alta hospitalar processada ≤ 5 min | — | 99% | 99,5% |
| Tarefa pós-alta ≤ 15 min | — | 95% | 99% |
| Alerta de conector crítico ≤ 5 min | ✔ | ✔ | ✔ |
| Triagem DLQ crítica ≤ 30 min úteis | ✔ | ✔ | ✔ |

Alertas baseados em **error budget** (burn rate), não em limiar fixo.

### 13.4 Operação

- **Runbooks** para cada alerta (o alerta aponta o runbook).
- Plantão definido (horário comercial na F1; 24×7 para eventos críticos a partir da F3, quando o pós-alta estiver em produção).
- Gestão de mudanças: janela, aprovação, rollback testado.
- *Postmortem* sem culpa para todo incidente relevante.
- Revisão mensal de capacidade e custos.

---

## 14. Estratégia de qualidade e testes

| Nível | Ferramentas | Critério |
|---|---|---|
| Unidade | JUnit 5, AssertJ, pytest, Vitest | Cobertura ≥ 80% em domínio (MPI, regras, validadores) |
| Arquitetura | ArchUnit | Fronteiras de módulo invioláveis |
| Integração | Testcontainers (PostgreSQL, Kafka, Keycloak, Temporal test server) | Todo consumidor/produtor testado com infraestrutura real |
| Contrato de eventos | Validação JSON Schema + compatibilidade Apicurio no CI | Nenhuma quebra retroativa |
| Contrato de API | OpenAPI + testes gerados (Schemathesis) | Respostas conformes ao contrato |
| Conectores | *Golden files* fonte→canônico; simuladores de fonte | 100% dos mapeamentos com teste |
| FHIR | Exemplos oficiais br-core/RNDS + negativos + teste de CapabilityStatement | Nenhum recurso anunciado sem teste |
| Políticas | `opa test` | Cada papel com casos permitidos e negados |
| Regras/protocolos | Casos de teste anexados à versão | Aprovação bloqueada sem testes |
| IA | Conjuntos de avaliação por agente, LLM-as-judge com amostragem humana | Métricas mínimas da seção 9.3 |
| E2E | Playwright com massa sintética | Jornadas críticas (seção 15) |
| Carga | k6 / Gatling | 2× o pico estimado na F0 com SLO atendido |
| Resiliência | Chaos Mesh (queda de broker, nó de banco, conector) | Sem perda de evento; recuperação automática |
| Segurança | Semgrep, Trivy, ZAP, pentest | Zero crítico/alto aberto no go-live |
| Acessibilidade | axe + avaliação manual | WCAG 2.1 AA |

**Dados sintéticos:** gerador próprio (ou Synthea adaptado) com nomes brasileiros, CNS/CPF com dígitos válidos porém fictícios, endereços do município, distribuição etária/territorial realista, duplicidades intencionais (para testar MPI), jornadas completas (APS → regulação → hospital → alta).

**Definição de pronto (DoD) de qualquer história:** código + testes + documentação OpenAPI/schema + métricas/logs + política OPA (se acessar dado) + revisão de segurança (se aplicável) + aceite do PO.

---

## 15. Roadmap detalhado por fase e sprint

Sprints de **2 semanas**. Equipes descritas na seção 16.

### Fase 0 — Descoberta e fundação técnica (semanas 1–6)

| Trilha | Atividades | Entregáveis |
|---|---|---|
| Negócio | Inventário de sistemas (versões, fornecedores, contratos, donos); mapa da rede (UBS, equipes, CNES, hospitais, prestadores); priorização de jornadas e indicadores; entrevistas de campo | Inventário, mapa da rede, backlog priorizado, personas |
| Jurídico/LGPD | Matriz de dados e bases legais; minutas de termos com operadores; início dos RIPDs | Matriz LGPD, RIPD-APS (rascunho) |
| Acessos externos | **Iniciar trâmites**: CADSUS, SISREG/e-SUS Regulação, RNDS (adesão, certificado ICP-Brasil), acesso a réplica do PEC, contatos técnicos dos hospitais | Ofícios protocolados, cronograma de acessos, plano B por conector |
| Arquitetura | ADRs 001–017, modelo canônico v0 (cidadão, unidade, profissional, agenda, atendimento), contratos de evento v0 | Repositório `contracts` v0.1 |
| Plataforma | Cluster `dev`, CI, Harbor, Keycloak dev, PostgreSQL/Kafka dev | Ambiente dev operacional |
| Spikes técnicos | (a) PoC leitura PEC/LEDI; (b) FHIR-0 (ADR-003); (c) volumetria; (d) gerador sintético | Relatórios de spike com decisão |
| Governança | Comitê gestor, DPO, PO por domínio, rito de priorização | Termo de abertura, RACI |

**Gate F0:** ADRs aprovados; modelo canônico v0 aprovado; acesso ao PEC confirmado (ou plano B ativado); RIPD-APS em revisão; piloto (1–3 UBS) definido.

### Fase 1 — Fundação + MVP APS (semanas 7–18, sprints 1–6)

| Sprint | Plataforma | Backend | Conectores | Frontend |
|---|---|---|---|---|
| S1 | HML/prod provisionados; CNPG, Strimzi, Apicurio, Keycloak MFA, OpenBao | `platform`: tenant/RLS, outbox (Debezium), inbox, auditoria encadeada, Problem Details, OTel | Connector SDK v0.1 (arquétipo, raw-zone, integration_message) | Design system v0 (tokens, base), shell com login BFF |
| S2 | Observabilidade completa; Argo CD; Kyverno | `reference` + `terminology` (CNES, CBO, SIGTAP, CID, CIAP-2 por competência) | Conectores CNES e SIGTAP/terminologias | Console de Integrações v0 (status, mensagens) |
| S3 | Backup/PITR testado; APISIX | `identity`: modelo, determinístico, source links, situação cadastral, eventos | Conector PEC (cidadão/cadastro) | Busca de cidadão + CitizenHeader |
| S4 | Hardening CIS; políticas de rede | `identity`: probabilístico (somente fila), merge/unmerge com auditoria; `integration`: erros, retry, DLQ, reprocessamento | Conector PEC (atendimentos); Agenda UBS | Cadastro Mestre: fila de revisão, merge/unmerge |
| S5 | Carga/caos em HML | `scheduling`, `journey` (timeline), `tasks` + `TaskSlaWorkflow`; OPA v1 (papéis APS/ACS/admin/operador) | Reconciliação diária; conector CADSUS (se liberado) | Timeline (APS + agenda), Console: erros/reprocessamento/reconciliação |
| S6 | Pentest; runbooks; plantão | Endurecimento, desempenho, correções; Metabase com painéis iniciais | Estabilização | Usabilidade com usuários; correções; acessibilidade |

**Go-live F1 (produção controlada):** 1–3 UBS piloto, usuários nominados, RIPD-APS aprovado.

**Gate F1:**
- ≥ 95% das mensagens do piloto processadas sem intervenção; DLQ triada em SLA.
- MPI: zero fusão automática errada (só determinístico automático); fila de revisão operando.
- Timeline exibindo eventos APS/agenda com latência ≤ 2 min.
- Pentest sem crítico/alto aberto; restore de backup testado.
- Aceite formal dos usuários-chave do piloto.

### Fase 2 — Regulação e exames (semanas 19–30, sprints 7–12)

| Sprint | Entregas |
|---|---|
| S7 | Modelo de regulação (request, queue, decision, capacity, outcome) + contratos de evento; conector SISREG (ou plano B) v0 |
| S8 | Conector e-SUS Regulação; `exams` (pedido, status, vínculo com regulação/agenda); FHIR-1 |
| S9 | Cockpit de Regulação v1 (fila por especialidade/procedimento/território/prioridade/espera; pendências); `RegulationSlaWorkflow` |
| S10 | `ExamFollowUpWorkflow`, `NoShowRecoveryWorkflow`; Workbench de Cuidado v1 (tarefas, no-show); detecção de duplicidade de agendamento e vagas ociosas (AGE-004/005) |
| S11 | AI Service + LiteLLM + Langfuse; **Agente de regulação (assistivo)**; Cockpit de Agentes v1; kill switch; conjunto de avaliação |
| S12 | Expansão para todas as UBS (ondas), carga, DR test #1, RIPD-Regulação e RIPD-IA, painéis de regulação |

**Gate F2:** fila regulatória reconciliada com a fonte (divergência < 1%); workflows de exame/no-show gerando tarefas úteis (validação amostral ≥ 80%); agente sem nenhuma ação proibida executada (verificado em log); todas as UBS conectadas.

### Fase 3 — Hospital, pós-alta e linhas de cuidado (semanas 31–44, sprints 13–19)

| Sprint | Entregas |
|---|---|
| S13 | Conector HIS (HL7 v2 ADT via MLLP/TLS, conector de borda) para hospital piloto; `hospital` (episódio, movimentação, alta, óbito) |
| S14 | `DischargeFollowUpWorkflow`; regras de risco pós-alta versionadas; tarefas para UBS/ACS; contrarreferência |
| S15 | `careplan`: protocolos configuráveis (gestante, hipertensão, diabetes como primeiras linhas); `CareGapDetectionWorkflow`; listas de busca ativa por UBS/eSF/microárea/ACS |
| S16 | Conectores LIS e RIS (metadados); ciclo completo de exame com laudo/resultado crítico; OpenSearch para timeline |
| S17 | Agentes de pós-alta e busca ativa; agente de protocolo (RAG pgvector sobre protocolos oficiais/municipais); consentimento e preferências de comunicação; comunicação aprovada sem conteúdo clínico |
| S18 | FHIR-2; Workbench completo; Timeline completa com cadeias causa-efeito |
| S19 | Expansão para demais hospitais/UPAs; plantão 24×7 para eventos críticos; RIPD-Hospital; DR com MirrorMaker |

**Gate F3:** 99% das altas do hospital piloto viram tarefa na UBS em ≤ 15 min; reinternações visíveis; linhas de cuidado prioritárias com lacunas detectadas e validadas por equipe clínica; protocolos alterados sem deploy.

### Fase 4 — Produção, FHIR/RNDS e BI completo (semanas 45–60, sprints 20–27)

| Sprint | Entregas |
|---|---|
| S20 | `production`: ingestão BPA/APAC; validação SIGTAP/CBO/CNES/habilitação/competência |
| S21 | AIH/SIH; conciliação com episódios hospitalares; `ProductionPreAuditWorkflow`; lotes só com aprovação humana |
| S22 | Retornos de processamento (rejeitado/aprovado/pago), painel de perdas e glosas; `CompetenceDeadlineWorkflow`; Auditoria de Produção (app) |
| S23 | Agente de auditoria (sugestão + evidência); FHIR-3 (Observation, DiagnosticReport, DocumentReference, `$everything`, `$validate`, Bundles, history, ETag) |
| S24 | Conector RNDS em homologação; certificado; reconciliação de envios |
| S25 | Lakehouse (MinIO/Iceberg, Trino, dbt bronze/silver/gold), OpenMetadata |
| S26 | Sala de Situação (mapas, capacidade, risco, desigualdade territorial); agente de BI (agregado) |
| S27 | RNDS em produção (escopo habilitado), DR test #2, pentest final, transferência de conhecimento |

**Gate F4:** pré-auditoria detecta inconsistências antes da transmissão com redução mensurável de rejeições; FHIR Gateway aprovado em teste com cliente externo e validação RNDS em homologação; indicadores da gestão publicados com dicionário e linhagem.

### Fase 5 — Escala e sustentação (contínuo)

Portal do Cidadão (gov.br), SI-PNI/notificações, prioridade 2/3 FHIR, multi-município (consórcio/região), offline para ACS, liberação gradual de autonomia de agentes conforme métricas, otimização de custos.

### 15.1 Jornadas E2E críticas (testes de aceite)

1. Cidadão atendido na UBS → aparece na timeline com identidade única.
2. Mesmo cidadão chega do hospital com CNS divergente → **não** é fundido; caso de revisão aberto.
3. Pedido de exame → não agendado em X dias → tarefa para UBS → agendado → no-show → busca ativa → realizado → laudo → tarefa de retorno.
4. Alta hospitalar → tarefa na UBS em ≤ 15 min → sem contato no SLA → escalonamento ACS → desfecho registrado.
5. Registro de produção inválido → pendência para auditor → corrigido → lote gerado só após aprovação.
6. Agente tenta ação proibida → negada por OPA → registrada.
7. Usuário fora do vínculo tenta acessar cidadão → negado; break-glass → permitido, alertado e auditado.

---

## 16. Equipe, papéis e governança do projeto

### 16.1 Composição recomendada (pico nas Fases 2–4)

| Equipe | Perfis | Qtde |
|---|---|---|
| **Produto e domínio** | Gerente de produto, POs por domínio (APS/cuidado, regulação, hospital, produção), especialista em saúde pública/SUS, designer UX/pesquisa | 5–6 |
| **Plataforma/SRE** | Engenheiro de plataforma K8s, SRE, DBA PostgreSQL, especialista Kafka | 3–4 |
| **Core** | Arquiteto de software (tech lead), engenheiros Java/Quarkus | 4–5 |
| **Integração** | Engenheiros de integração (Camel, HL7 v2, SOAP, arquivos DATASUS) | 3–4 |
| **FHIR/Terminologia** | Especialista FHIR (br-core/RNDS), engenheiro Java | 2 |
| **Frontend** | Engenheiros React/Next.js, especialista em design system/acessibilidade | 3–4 |
| **Dados/BI** | Engenheiro de dados (dbt/Trino), analista de BI | 2 |
| **IA** | Engenheiro de ML/LLM (Python), avaliação/qualidade de IA | 2 |
| **Segurança e privacidade** | Engenheiro de segurança (AppSec/DevSecOps), apoio jurídico/DPO | 1–2 |
| **QA** | Engenheiros de qualidade/automação | 2 |

Na Fase 0/1, ~15–18 pessoas; pico ~30. Para sustentabilidade, **servidores municipais** devem compor as equipes desde a F1 (transferência de conhecimento contínua, pareamento, documentação).

### 16.2 Governança

| Fórum | Frequência | Decide |
|---|---|---|
| Comitê gestor (Secretário/SMS, TI, DPO, áreas) | Mensal | Prioridades, escopo de fase, riscos altos, gates |
| Comitê técnico-clínico | Quinzenal | Protocolos, regras, SLAs, autonomia de agentes |
| Conselho de arquitetura | Quinzenal | ADRs, contratos, padrões |
| Comitê de privacidade (DPO) | Mensal + sob demanda | RIPDs, incidentes, novos usos de dados/IA |
| Revisão de sprint | Quinzenal | Aceite de entregas |

RACI resumida: **SMS** é dona das regras e decisões clínicas/regulatórias; **equipe técnica** é dona da implementação e operação; **DPO** tem poder de veto em uso de dados.

---

## 17. Registro de riscos

| # | Risco | Prob. | Impacto | Mitigação | Contingência |
|---|---|:---:|:---:|---|---|
| R1 | Acesso a SISREG/e-SUS Regulação não liberado ou sem API | Alta | Alto | Trâmite na semana 1; envolvimento do gestor; mapear exportações | Conector por exportação periódica; cockpit com latência D-1 |
| R2 | Fornecedores de HIS não cooperam ou cobram integração | Alta | Alto | Exigir HL7/FHIR em contratos; começar pelo hospital municipal mais cooperativo | Ingestão de arquivos/relatórios; cláusula contratual em renovações |
| R3 | Fusão errada no MPI | Média | Muito alto | Só determinístico automático; revisão humana; unmerge; métricas | Unmerge + reprocessamento de projeções + comunicação |
| R4 | Vazamento/acesso indevido | Média | Muito alto | OPA, RLS, MFA, mascaramento, auditoria, pentest, alertas | Plano de incidente, comunicação ANPD/titulares |
| R5 | Escopo crescente ("tudo ao mesmo tempo") | Alta | Alto | Gates de fase, backlog priorizado pelo comitê, stack escalonada | Replanejamento formal com corte de escopo |
| R6 | Falta de capacidade operacional municipal (K8s/Kafka) | Alta | Alto | Operadores, GitOps, runbooks, capacitação, suporte contratado | Serviço gerenciado em nuvem BR para componentes críticos |
| R7 | Performance do validador FHIR / complexidade de perfis | Média | Médio | Spike FHIR-0; cache de pacotes; validação assíncrona para cargas | Validação de perfil completa só em escrita externa; projeções pré-validadas |
| R8 | Mudanças de versão PEC/LEDI/SIGTAP/RNDS | Alta | Médio | Mapeamentos versionados, testes de contrato, monitoramento de releases DATASUS | Conector com suporte multiversão |
| R9 | Resistência de usuários / baixa adoção | Média | Alto | Pesquisa de campo, piloto, champions locais, treinamento | Ajuste de UX, foco em funcionalidades que economizam tempo |
| R10 | IA gera recomendação incorreta | Média | Alto | Autonomia graduada, aprovação humana, avaliação contínua, kill switch | Desligar agente; revisão das ações registradas |
| R11 | Qualidade de dados de origem ruim (CNS faltante, endereços) | Alta | Médio | Módulo de qualidade, painéis por unidade, devolutiva às equipes | Busca ativa cadastral como tarefa |
| R12 | Infraestrutura do datacenter insuficiente (energia, link, storage) | Média | Alto | Avaliação na F0; DR; nuvem BR para DR | Migração híbrida |
| R13 | Dependência de pessoas-chave | Média | Alto | Documentação, ADRs, pareamento, servidores no time | Contrato de suporte |
| R14 | Questionamento jurídico de uso de dados/IA | Baixa | Alto | Bases legais, RIPD, transparência, DPO no comitê | Suspensão do uso específico (kill switch) |

---

## 18. Matriz de rastreabilidade de requisitos

| Grupo | Requisitos | Componente(s) | Fase |
|---|---|---|---|
| MPI | MPI-001…010, 012 | `identity`, Cadastro Mestre | F1 |
| MPI | MPI-011 (CADSUS) | `connector-cadsus` | F1/F2 (depende de acesso) |
| Jornada | JOR-001…008, 010 | `journey`, OPA, Timeline | F1 (APS/agenda) → F3 (completo) |
| Jornada | JOR-009 (exportação) | `audit`, fluxo de exportação | F3 |
| Agenda | AGE-001…003, 007, 009 | `scheduling`, conectores de agenda | F1–F2 |
| Agenda | AGE-004…006, 010 | `scheduling`, workflows, Workbench | F2 |
| Agenda | AGE-008 (lembretes) | `communication`, `consent` | F3 |
| Regulação | REG-001…012 | `regulation`, Cockpit, workflows, OPA (REG-009) | F2 |
| Cuidado | CUI-001…010 | `careplan`, `tasks`, workflows, Workbench | F3 |
| Hospital | HOS-001…010 | `hospital`, conector HIS, workflow pós-alta | F3 |
| Exames | EXA-001…004, 010 | `exams`, workflow de exame | F2 |
| Exames | EXA-005…009 | `exams`, conectores LIS/RIS | F3 |
| Produção | PRO-001…010 | `production`, workflows, Auditoria de Produção | F4 |
| BI | 4.9 | Metabase (F1–F3), lakehouse (F4) | F1→F4 |
| IA | AIA-001…010 | `ai-service`, OPA, Keycloak, Langfuse, Cockpit de Agentes | F2→F4 |
| Kafka | KAF-001…012 | `platform` (outbox/inbox), Strimzi, Apicurio | F1 |
| Segurança | SEC-001…012 | Keycloak, OpenBao, OPA, APISIX, auditoria | F1 (base) → F3 (break-glass, exportação) |
| FHIR | 5.3 | `fhir-gateway` | F1 (spike) → F4 |
| Observabilidade | 5.9 | OTel stack | F1 |

---

## 19. Critérios de entrada em produção (go-live)

Checklist aplicado a **cada** go-live de fase:

- [ ] RIPD do domínio aprovado pelo DPO.
- [ ] Pentest concluído sem achados críticos/altos abertos.
- [ ] Testes E2E das jornadas da fase passando em HML com dados sintéticos.
- [ ] Teste de carga a 2× o pico estimado com SLO atendido.
- [ ] Backup e restore testados no período; DR testado (a partir da F2).
- [ ] Dashboards, alertas e runbooks publicados; plantão definido.
- [ ] Políticas OPA revisadas e testadas para todos os papéis da fase.
- [ ] Usuários treinados; material de apoio publicado; canal de suporte ativo.
- [ ] Plano de rollback documentado e ensaiado.
- [ ] Aceite formal do PO e do comitê gestor.
- [ ] Para IA: conjunto de avaliação acima do limiar, kill switch testado, ações proibidas verificadas.

---

## 20. Próximos passos imediatos (30 dias)

1. **Formalizar governança:** comitê gestor, DPO, POs e tech lead nomeados; RACI assinado.
2. **Protocolar acessos externos** (CADSUS, SISREG/e-SUS Regulação, RNDS, réplica do PEC, hospitais) — caminho crítico do projeto.
3. **Inventário e volumetria** dos sistemas e da rede (planilha-mestra + entrevistas).
4. **Aprovar ADR-001 a ADR-017** em conselho de arquitetura.
5. **Provisionar `dev`** (cluster, CI, Harbor, PostgreSQL, Kafka, Keycloak) e o repositório `contracts` com o envelope de evento e o modelo canônico v0.
6. **Executar spikes**: PEC/LEDI, FHIR-0 (biblioteca de referência HL7 sem servidor HAPI), gerador de dados sintéticos.
7. **Escolher as UBS piloto** (diversidade de território, equipe engajada, conectividade estável).
8. **Iniciar RIPD-APS** e a matriz dado × finalidade × base legal.

---

### Apêndice A — Envelope de evento (JSON Schema, resumo)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://contracts.susnexus.local/events/envelope/1.0",
  "type": "object",
  "required": ["event_id","event_type","event_version","occurred_at","published_at",
               "tenant","source","data","privacy","trace"],
  "properties": {
    "event_id":      {"type": "string", "pattern": "^evt_[0-9A-HJKMNP-TV-Z]{26}$"},
    "event_type":    {"type": "string", "pattern": "^sus\\.[a-z-]+\\.[a-z-]+\\.[a-z_]+$"},
    "event_version": {"type": "string"},
    "occurred_at":   {"type": "string", "format": "date-time"},
    "published_at":  {"type": "string", "format": "date-time"},
    "tenant":  {"type": "object", "required": ["municipality_id"],
                "properties": {"municipality_id": {"type": "string"},
                               "health_secretariat_id": {"type": "string"}}},
    "subject": {"type": "object",
                "properties": {"municipal_citizen_id": {"type": "string"},
                               "identifiers": {"type": "array"}}},
    "source":  {"type": "object", "required": ["system","source_record_id"]},
    "data":    {"type": "object"},
    "data_ref":{"type": "string", "description": "Referência a payload completo no object storage"},
    "privacy": {"type": "object", "required": ["classification","purpose"],
                "properties": {"classification": {"enum": ["public","internal","restricted","highly_restricted"]},
                               "purpose": {"type": "array", "items": {"type": "string"}}}},
    "trace":   {"type": "object", "required": ["correlation_id","schema_version"]},
    "replay":  {"type": "boolean", "default": false}
  }
}
```

> Observação: em eventos publicados em tópicos de domínio, recomenda-se **não** trafegar CNS/CPF em claro em `subject.identifiers` — usar apenas `municipal_citizen_id` e, quando indispensável, identificador mascarado/hash. A especificação original inclui o CNS no exemplo; o ajuste segue KAF-009 e SEC-007.

### Apêndice B — Glossário rápido

| Termo | Significado |
|---|---|
| ADT | Admission, Discharge, Transfer (mensagens HL7 v2 hospitalares) |
| BFF | Backend for Frontend |
| CDC | Change Data Capture |
| DLQ | Dead Letter Queue |
| LEDI | Layout e-SUS APS de Dados e Interface |
| MPI | Master Patient Index (cadastro mestre do cidadão) |
| OPA | Open Policy Agent |
| RIPD | Relatório de Impacto à Proteção de Dados Pessoais |
| RLS | Row-Level Security (PostgreSQL) |
| RNDS | Rede Nacional de Dados em Saúde |
| WORM | Write Once, Read Many |
