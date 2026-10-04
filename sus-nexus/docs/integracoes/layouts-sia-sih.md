# Layouts SIA/SIH — exportação de produção e retornos oficiais

Documento de referência dos layouts implementados no `core-municipal` (exportação de lotes,
`ExportLayouts`) e no `connector-sia` (leitura de retornos, `layouts/sia-layouts.yaml`). Pesquisa feita em
2026-10-04.

## 1. Situação das fontes (leia antes de usar)

O ambiente em que este trabalho foi feito tem **saída de rede restrita**: os portais do DATASUS e do
Ministério da Saúde (`sia.datasus.gov.br`, `sihd.datasus.gov.br`, `w3.datasus.gov.br`, `www2.datasus.gov.br`,
`datasus.saude.gov.br`, `sigtap.datasus.gov.br`, `ftp.datasus.gov.br`, `www.gov.br`, `bvsms.saude.gov.br`) e
espelhos comuns (Scribd, FEHOSP, TOTVS TDN, web.archive.org) responderam **403 — host fora da lista
permitida**. Só foi possível usar a busca na web (que confirma a existência, o título e a data dos documentos
oficiais) e o `raw.githubusercontent.com` (arquivos de repositórios públicos). Por isso cada layout abaixo traz o
**grau de confirmação**:

| Grau | Significado |
|---|---|
| **Confirmado (oficial)** | Conferido em artefato oficial do DATASUS acessado byte a byte (arquivo `.dbc` do FTP de disseminação, com SHA-256 igual ao publicado) ou em tabela oficial do TabWin (CNV/DBF do `TAB_SIA.zip`/`TAB_SIH.zip`) transcrita com SHA-256 do pacote. |
| **Transcrição do PDF oficial — conferência pendente** | O PDF oficial existe (título/data confirmados pela busca), mas não pôde ser baixado; as posições vêm de transcrição publicada por terceiros e foram conferidas pela consistência interna (campos contíguos, soma = tamanho do registro). **Conferir com o PDF original antes da 1ª transmissão.** |
| **A CONFIRMAR** | Sem fonte oficial acessível; não implementado como layout oficial (ou mantido só como alternativa local). |

### Fontes

| # | Documento | Onde | Como foi usado |
|---|---|---|---|
| F1 | **Layout de Exportação BPA** — `Layout_Exportacao_BPA.pdf`, DATASUS/SIA, publicado em **12/12/2024** (163,2 KB) | <https://sia.datasus.gov.br/documentos/listar_ftp_bpa.php> (área "Arquivo(s) de BPA para download") | Título/data confirmados pela busca; PDF **inacessível** (403). Posições transcritas de cópia do PDF: `docs/data-dictionary-bpa.md` do projeto público [VINIClUS/CnesData](https://github.com/VINIClUS/CnesData) (extraído do `Layout_Exportacao_BPA.pdf` e do `Manual_Operacional_BPA.pdf` — CGSI/DRAC/SAS/MS, set/2012). |
| F2 | **Layout da interface texto do APAC e do SIA** (layout de exportação APAC), DATASUS/SIA — versão com `apa_semcpf` (válido a partir de 07/2026); a busca indica PDF de 13/10/2025 (414,2 KB) na mesma área | <https://sia.datasus.gov.br/versao/listar_ftp_apac.php> | PDF **inacessível** (403). Posições transcritas de implementação do layout interno SIA/APAC "versão consultada em 08/07/2026": `Editor_APAC.py` do projeto público [ErikaVazCravo/Arquivos_APAC](https://github.com/ErikaVazCravo/Arquivos_APAC). |
| F3 | **Layout da interface texto do SISAIH01** (vários, por competência; ex.: `LAYOUT_SISAIH01_201010.pdf`) | <http://sihd.datasus.gov.br/documentos/documentos_sisaih01.php>, <http://w3.datasus.gov.br/sihd/Manuais/LAYOUT_SISAIH01_201010.pdf> | **Inacessível** (403) e sem transcrição verificável localizada — só nomes de campos esparsos (`NU_LOTE`, `QT_LOTE`, `APRES_LOTE`, `SEQ_LOTE`, `ORG_EMIS_AIH`, `CNES_HOSP`, `MUN_HOSP`, `NU_AIH`, `IDENT_AIH`, `ESPEC_AIH`…) sem posições. **Não implementado.** |
| F4 | Arquivos de disseminação **SIASUS PA** — `ftp://ftp.datasus.gov.br/dissemin/publicos/SIASUS/200801_/Dados/PARR2401.dbc` (SHA-256 `3f875df629008dbc118bb12e41e7df44e80bac8a5c1d914ad745c47678d76d0d`) | cópia byte a byte em [raphaelfh/omnisus `tests/fixtures/dbc/sia_pa_rr_2024_01_mini.dbc`](https://github.com/raphaelfh/omnisus/tree/main/tests/fixtures/dbc) | **Confirmado**: SHA-256 do arquivo baixado = SHA-256 do servidor registrado; descomprimido aqui (`DbcDecompressor`) → DBF de 8.474.331 bytes, SHA-256 `5963607e…89e6` (igual ao "golden" de referência), 21.341 registros, 60 campos. |
| F5 | **SIHSUS RD/RJ/ER** — `RDRR2401.dbc` (SHA-256 `37741f8b…eccb5`), `RJRR2401.dbc` (`bae9610e…891a`), `ERRR2401.dbc` (`5f6f48fa…23fa2`) do mesmo FTP (`SIHSUS/200801_/Dados`) | idem F4 | **Confirmado** como F4: DBF expandidos com SHA-256 `3edb49b8…80ec`, `ee809a16…eb0b`, `eeb9592a…30bf` (iguais aos de referência); 3.714, 30 e 31 registros. |
| F6 | **Informe Técnico SIASUS 2019-07** — `ftp://ftp.datasus.gov.br/dissemin/publicos/SIASUS/200801_/Doc/Informe_Tecnico_SIASUS_2019_07.pdf` (SHA-256 `70fe69db…c8dc`) | dicionário dos arquivos de disseminação do SIA | Inacessível daqui; usado pelo conteúdo citado com página no registro de fontes do projeto omnisus (`src/omnisus/data/dicionarios/sources/registry.json`). |
| F7 | **Informe Técnico SIH 2016-03** — `ftp://ftp.datasus.gov.br/dissemin/publicos/SIHSUS/200801_/Doc/IT_SIHSUS_1603.pdf` (SHA-256 `1e89d5f2…6220`) | dicionário RD/RJ/SP/ER | Idem F6 (p. 1–5: RD, RJ, SP, ER; `N_AIH` 13, `ANO_CMPT`/`MES_CMPT` = processamento). |
| F8 | **TAB_SIA.zip** (`…/SIASUS/200801_/Auxiliar/TAB_SIA.zip`, SHA-256 `e5812b51…3d98`) e **TAB_SIH.zip** (`…/SIHSUS/200801_/Auxiliar/TAB_SIH.zip`, SHA-256 `f05b32f3…76df`) — definições/conversões oficiais do TabWin | idem | Domínios: `INDICA.CNV` (`PA_INDICA`), `SITREJEICAO.CNV` (`ST_SITUAC`), `SITBLOQUEIO.CNV` (`ST_BLOQ`), `MOTBLOQUEIO.CNV` (`ST_MOT_BLO`), `MOTERRO.DBF` (`CO_ERRO`), transcritos com status `verified_in_source` em 2026-09-21 nos dicionários do omnisus. |
| F9 | `blast.c` (Mark Adler, zlib) — decodificador de referência do PKWare DCL implode, usado pelo `dbc2dbf` do pacote R `read.dbc` | <https://github.com/madler/zlib/tree/master/contrib/blast> | Base do `DbcDecompressor` (porte Java), validado contra F4/F5. |

## 2. Exportação — BPA-Magnético (`bpa_mag_v202412`)

Grau: **transcrição do PDF oficial (F1) — conferência pendente**. Texto ASCII, largura fixa, cada registro
terminado por CR+LF (`prd-fim`/`cbc-fim`, 2 bytes, não contados abaixo). Numérico (N): zeros à esquerda;
alfanumérico (A): à esquerda, completado com espaços, maiúsculas sem acento. Campo sem informação: espaços.

### Cabeçalho — 130 + CRLF

| Campo | Ini | Fim | Tam | Tipo | Regra |
|---|---|---|---|---|---|
| `cbc-hdr` | 1 | 2 | 2 | N | `01` |
| `cbc-hdr` | 3 | 7 | 5 | A | `#BPA#` |
| `cbc-mvm` | 8 | 13 | 6 | N | competência AAAAMM |
| `cbc-lin` | 14 | 19 | 6 | N | total de linhas BPA gravadas (02 + 03) |
| `cbc-flh` | 20 | 25 | 6 | N | total de folhas gravadas |
| `cbc-smt-vrf` | 26 | 29 | 4 | N | **campo de controle** (domínio 1111–2221): Σ(código do procedimento + quantidade) de todas as linhas; resto da divisão por 1111; somar 1111 |
| `cbc-rsp` | 30 | 59 | 30 | A | nome do órgão de origem (`sus.production.export-origin-name`) |
| `cbc-sgl` | 60 | 65 | 6 | A | sigla do órgão de origem |
| `cbc-cgccpf` | 66 | 79 | 14 | N | CNPJ/CPF do órgão/prestador |
| `cbc-dst` | 80 | 119 | 40 | A | nome do órgão de destino (`sus.production.export-destination-name`) |
| `cbc-dst-in` | 120 | 120 | 1 | A | `E` estadual / `M` municipal |
| `cbc_versao` | 121 | 130 | 10 | A | versão do sistema (livre) — `SUSNEXUS01` |

### BPA-C (`02`) — 48 + CRLF

| Campo | Ini | Fim | Tam | Tipo | Regra |
|---|---|---|---|---|---|
| `prd-ident` | 1 | 2 | 2 | N | `02` |
| `prd-cnes` | 3 | 9 | 7 | N | CNES |
| `prd-cmp` | 10 | 15 | 6 | N | competência AAAAMM |
| `prd_cbo` | 16 | 21 | 6 | A | CBO do profissional |
| `prd-flh` | 22 | 24 | 3 | N | folha 001–999 |
| `prd-seq` | 25 | 26 | 2 | N | sequencial 01–20 |
| `prd-pa` | 27 | 36 | 10 | N | procedimento SIGTAP |
| `prd-idade` | 37 | 39 | 3 | N | idade 0–130 (`000` quando o procedimento não exige) |
| `prd-qt` | 40 | 45 | 6 | N | quantidade |
| `prd-org` | 46 | 48 | 3 | A | origem (`BPA`, `PNI`, `SIE`, `SIB`, `MIN`, `PAC`, `SCL`, `EXT`) — exportado `BPA` |

### BPA-I (`03`) — 350 + CRLF

| Campo | Ini | Fim | Tam | Tipo | Regra / preenchimento pelo barramento |
|---|---|---|---|---|---|
| `prd-ident` | 1 | 2 | 2 | N | `03` |
| `prd-cnes` | 3 | 9 | 7 | N | CNES |
| `prd-cmp` | 10 | 15 | 6 | N | competência |
| `prd_cnsmed` | 16 | 30 | 15 | N | CNS do profissional |
| `prd_cbo` | 31 | 36 | 6 | A | CBO |
| `prd_dtaten` | 37 | 44 | 8 | N | data do atendimento AAAAMMDD |
| `prd-flh` | 45 | 47 | 3 | N | folha (quebra a cada 20 linhas **e** por profissional CNS+CBO) |
| `prd-seq` | 48 | 49 | 2 | N | sequencial na folha |
| `prd-pa` | 50 | 59 | 10 | N | procedimento |
| `prd-cnspac` | 60 | 74 | 15 | N | CNS do paciente — **CNS ou CPF**: com um, o outro em branco |
| `prd-sexo` | 75 | 75 | 1 | A | `M`/`F` |
| `prd-ibge` | 76 | 81 | 6 | N | município de residência (IBGE sem DV) |
| `prd-cid` | 82 | 85 | 4 | A | CID-10 sem ponto |
| `prd-idade` | 86 | 88 | 3 | N | idade |
| `prd-qt` | 89 | 94 | 6 | N | quantidade |
| `prd-caten` | 95 | 96 | 2 | N | caráter: 01 eletivo, 02 urgência, 03 acidente de trabalho; `other` do barramento → branco (04–06 não determináveis) |
| `prd-naut` | 97 | 109 | 13 | N | nº de autorização (opcional) |
| `prd-org` | 110 | 112 | 3 | A | `BPA` |
| `prd-nmpac` | 113 | 142 | 30 | A | nome do paciente (MPI) |
| `prd-dtnasc` | 143 | 150 | 8 | N | nascimento AAAAMMDD |
| `prd-raca` | 151 | 152 | 2 | N | 01–05, 99 sem informação — exportado `99` (raça/cor não trafega na produção) |
| `prd-etnia` | 153 | 156 | 4 | N | obrigatória se raça = 05 — branco |
| `prd-nac` | 157 | 159 | 3 | N | nacionalidade — branco (completar no BPA-Mag) |
| `prd_srv` | 160 | 162 | 3 | N | serviço — branco |
| `prd_clf` | 163 | 165 | 3 | N | classificação — branco |
| `prd_equipe_Seq` | 166 | 173 | 8 | N | sequencial da equipe — branco |
| `prd_equipe_Area` | 174 | 177 | 4 | N | área da equipe — branco |
| `prd_cnpj` | 178 | 191 | 14 | N | CNPJ da empresa de OPM — branco |
| `prd_cep_pcnte` | 192 | 199 | 8 | N | CEP |
| `prd_lograd_pcnte` | 200 | 202 | 3 | N | código do logradouro — branco |
| `prd_end_pcnte` | 203 | 232 | 30 | A | endereço |
| `prd_compl_pcnte` | 233 | 242 | 10 | A | complemento |
| `prd_num_pcnte` | 243 | 247 | 5 | A | número |
| `prd_bairro_pcnte` | 248 | 277 | 30 | A | bairro |
| `prd_ddtel_pcnte` | 278 | 288 | 11 | N | telefone — branco (contato só mascarado no MPI) |
| `prd_email_pcnte` | 289 | 328 | 40 | A | e-mail — branco |
| `prd_ine` | 329 | 338 | 10 | N | INE (a partir de 08/2015) — branco |
| `prd_cpf_pcnte` | 339 | 349 | 11 | N | CPF do paciente (só quando não há CNS) |
| `prd_situacao_rua` | 350 | 350 | 1 | A | `S`/`N` (a partir de 12/2024) — branco |

> No PDF a numeração de sequência `38` aparece duplicada (`prd_cpf_pcnte` e `prd_situacao_rua`); as posições
> são contíguas e somam 350.

## 3. Exportação — APAC (`apac_mag_v202607`)

Grau: **transcrição do layout oficial (F2) — conferência pendente**. Mesmas regras de preenchimento do BPA.
Ordem: cabeçalho; para cada APAC, um `14` seguido dos seus `13`. Partes variáveis (`06` laudo geral, `07`
quimioterapia, `08` radioterapia, `09`–`12`, `17`–`20`) **não** são geradas (sem dados de laudo no barramento).

### Cabeçalho (`01`) — 137 + CRLF

| Campo | Ini | Fim | Tam | Tipo | Regra |
|---|---|---|---|---|---|
| `cbc_hdr` | 1 | 2 | 2 | N | `01` |
| `cbc_apac` | 3 | 7 | 5 | A | `#APAC` |
| `cbc_cmp` | 8 | 13 | 6 | N | competência AAAAMM |
| `cbc_lin` | 14 | 19 | 6 | N | quantidade de APAC (registros 14) |
| `cbc_smt_vrf` | 20 | 23 | 4 | N | **controle**: (Σ nº de cada APAC, uma vez + Σ(código + quantidade) dos registros 13) mod 1111 + 1111 |
| `cbc_rsp` | 24 | 53 | 30 | A | órgão de origem |
| `cbc_sgl` | 54 | 59 | 6 | A | sigla do órgão de origem |
| `cbc_cgccpf` | 60 | 73 | 14 | N | CNPJ do responsável |
| `cbc_dst` | 74 | 113 | 40 | A | órgão de destino |
| `cbc_dst_in` | 114 | 114 | 1 | A | `M`/`E` |
| `cbc_dtger` | 115 | 122 | 8 | N | data de geração AAAAMMDD |
| `cbc_versao` | 123 | 137 | 15 | A | versão |

### Corpo da APAC (`14`) — 537 + CRLF

| Campo | Ini | Fim | Tam | Tipo | Preenchimento pelo barramento |
|---|---|---|---|---|---|
| `apa_corpo` | 1 | 2 | 2 | N | `14` |
| `apa_cmp` | 3 | 8 | 6 | N | competência |
| `apa_num` | 9 | 21 | 13 | N | nº da APAC (com DV) |
| `apa_coduf` | 22 | 23 | 2 | N | UF (IBGE) do tenant |
| `apa_codcnes` | 24 | 30 | 7 | N | CNES executante |
| `apa_pr` | 31 | 38 | 8 | N | data do processamento — branco |
| `apa_dtiinval` / `apa_dtfimval` | 39 / 47 | 46 / 54 | 8 / 8 | N | validade — branco |
| `apa_tipate` | 55 | 56 | 2 | N | tipo de atendimento — branco |
| `apa_tipapac` | 57 | 57 | 1 | N | 1 inicial, 2 continuidade, 3 única — branco |
| `apa_nomepcnte` | 58 | 87 | 30 | A | nome do paciente |
| `apa_nomemae` | 88 | 117 | 30 | A | nome da mãe |
| `apa_logpcnte` | 118 | 147 | 30 | A | logradouro |
| `apa_numpcnte` | 148 | 152 | 5 | A | número |
| `apa_cplpcnte` | 153 | 162 | 10 | A | complemento |
| `apa_ceppcnte` | 163 | 170 | 8 | N | CEP |
| `apa_munpcnte` | 171 | 177 | 7 | N | município (IBGE 7) |
| `apa_datanascim` | 178 | 185 | 8 | N | nascimento |
| `apa_sexopcnte` | 186 | 186 | 1 | A | `M`/`F` |
| `apa_nomeresp_med` | 187 | 216 | 30 | A | médico responsável — branco |
| `apa_codprinc` | 217 | 226 | 10 | N | procedimento principal (1º registro da APAC no lote) |
| `apa_motsaida` | 227 | 228 | 2 | N | motivo de saída — branco |
| `apa_dtobitoalta` | 229 | 236 | 8 | A | data alta/óbito — branco |
| `apa_nomediretor` | 237 | 266 | 30 | A | autorizador — branco |
| `apa_cnspct` | 267 | 281 | 15 | N | CNS do paciente (ou branco com CPF) |
| `apa_cnsres` | 282 | 296 | 15 | N | CNS do médico responsável — branco |
| `apa_cnsdir` | 297 | 311 | 15 | N | CNS do autorizador — branco |
| `apa_cidca` | 312 | 315 | 4 | A | CID causas associadas — branco |
| `apa_npront` | 316 | 325 | 10 | N | prontuário — branco |
| `apa_codsol` | 326 | 332 | 7 | N | CNES solicitante — branco |
| `apa_datsol` / `apa_dataut` | 333 / 341 | 340 / 348 | 8 / 8 | N | solicitação / autorização — branco |
| `apa_codemis` | 349 | 358 | 10 | A | emissor — branco |
| `apa_carate` | 359 | 360 | 2 | N | caráter (01, 02, 03; `other` → branco) |
| `apa_apacant` | 361 | 373 | 13 | N | APAC anterior — branco |
| `apa_raca` | 374 | 375 | 2 | N | `99` (sem informação) |
| `apa_nomeresp_pac` | 376 | 405 | 30 | A | responsável — branco |
| `apa_nascpcnte` | 406 | 408 | 3 | N | nacionalidade — branco |
| `apa_etnia` | 409 | 412 | 4 | N | branco |
| `apa_cdlogr` | 413 | 415 | 3 | N | branco |
| `apa_bairro` | 416 | 445 | 30 | A | bairro |
| `apa_dddtelcontato` / `apa_telcontato` | 446 / 448 | 447 / 456 | 2 / 9 | N | branco |
| `apa_email` | 457 | 496 | 40 | A | branco |
| `apa_cnsexec` | 497 | 511 | 15 | N | CNS do executante do procedimento principal |
| `apa_cpfpcnte` | 512 | 522 | 11 | N | CPF (só sem CNS) |
| `apa_ine` | 523 | 532 | 10 | N | branco |
| `apa_strua` | 533 | 533 | 1 | A | branco |
| `apa_fntorca` | 534 | 535 | 2 | N | branco |
| `apa_emenpar` | 536 | 536 | 1 | A | branco |
| `apa_semcpf` | 537 | 537 | 1 | A | branco (a partir de 07/2026) |

### Procedimentos da APAC (`13`) — 97 + CRLF

| Campo | Ini | Fim | Tam | Tipo | Preenchimento |
|---|---|---|---|---|---|
| `pap_corpo` | 1 | 2 | 2 | N | `13` |
| `pap_cmp` | 3 | 8 | 6 | N | competência |
| `pap_num` | 9 | 21 | 13 | N | nº da APAC |
| `pap_codproc` | 22 | 31 | 10 | N | procedimento |
| `pap_cbo` | 32 | 37 | 6 | N | CBO |
| `pap_qtdprod` | 38 | 44 | 7 | N | quantidade |
| `pap_CGC` | 45 | 58 | 14 | N | CNPJ cessão de crédito — branco |
| `pap_NF` | 59 | 64 | 6 | A | nota fiscal — branco |
| `pap_CIDP` | 65 | 68 | 4 | A | CID principal |
| `pap_CIDS` | 69 | 72 | 4 | A | branco |
| `pap_SRV` / `pap_CLF` | 73 / 76 | 75 / 78 | 3 / 3 | N | branco |
| `pap_equipe_Seq` / `pap_equipe_Area` | 79 / 87 | 86 / 90 | 8 / 4 | N | branco |
| `pap_cnes_terc` | 91 | 97 | 7 | N | branco |

## 4. Exportação — AIH (SISAIH01 → SIHD)

Grau: **A CONFIRMAR — não implementado.** O layout oficial (F3) não pôde ser obtido; inventar posições faria o
SISAIH01/SIHD rejeitar o arquivo (ou pior, aceitar campos deslocados). Lotes de AIH seguem em `csv_ref_v1`
(alternativa local) e a digitação/importação oficial continua no SISAIH01. Para implementar: obter o PDF
"Layout da interface texto do SISAIH01" da competência vigente em
<http://sihd.datasus.gov.br/documentos/documentos_sisaih01.php> e acrescentar `sisaih01_v<competência>` em
`ExportLayouts` com teste por posição, como BPA/APAC.

## 5. Retornos oficiais (connector-sia)

O que o DATASUS publica de forma aberta e verificável como resultado do processamento são os **arquivos de
disseminação** por UF e mês de processamento, no FTP `ftp.datasus.gov.br/dissemin/publicos/…/Dados`, em `.dbc`
(DBF comprimido com PKWare DCL implode; o TabWin "Comprime/Expande DBC" gera o `.dbf`). Eles **não** trazem
pagamento (só aprovação), nem o id do registro do barramento, nem CNS do paciente em claro. Os relatórios de
crítica/glosa dos aplicativos (SIA, SIHD, SISAIH01) não têm layout oficial publicado que eu tenha conseguido
localizar/acessar — continuam cobertos pela alternativa CSV local (`retorno_sia_csv`, **A CONFIRMAR**).

Grau: **Confirmado (oficial)** para a estrutura física (F4/F5) e os domínios (F8). **A CONFIRMAR**: chave de
conciliação (convenção do município: exportar AIH/APAC com `id_registro` = nº da AIH/APAC).

| Kind | Arquivo | Campos usados (tipo/tamanho físico) | Regra → `ProductionOutcomeRegistration` |
|---|---|---|---|
| `retorno_sia_pa` | `PA<UF><AAMM>[a-d].dbc` | `PA_INDICA` C1, `PA_AUTORIZ` C13, `PA_MVM` C6, `PA_CMP` C6, `PA_CODUNI` C7, `PA_PROC_ID` C10, `PA_QTDAPR` N11 | `PA_INDICA` (INDICA.CNV): `0` não aprovado → `rejected`; `5` aprovado totalmente, `6` aprovado parcialmente → `accepted` (`approved_quantity` = `PA_QTDAPR`); alvo = `PA_AUTORIZ`; linhas com autorização vazia/zerada (BPA) ignoradas; `processed_at` = `PA_MVM` + dia 01 |
| `retorno_sih_rd` | `RD<UF><AAMM>.dbc` | `N_AIH` C13, `ANO_CMPT` C4, `MES_CMPT` C2, `CNES` C7, `REMESSA` C21 | AIH aprovada → `accepted`; alvo = `N_AIH`; `protocol_number` = `REMESSA` |
| `retorno_sih_rj` | `RJ<UF><AAMM>.dbc` | `ST_SITUAC` C1, `ST_BLOQ` C1, `ST_MOT_BLO` C2, `N_AIH` C13, `ANO_CMPT`, `MES_CMPT`, `REMESSA` | só `ST_SITUAC = 1` (SITREJEICAO.CNV: AIH rejeitada) → `rejected`, `reason_code` = `ST_MOT_BLO` (MOTBLOQUEIO.CNV, ex.: 01 duplicidade, 62 permanência a menor injustificada); `0` (autorizada) ignorada |
| `retorno_sih_er` | `ER<UF><AAMM>.dbc` | `AIH` C13, `CO_ERRO` C6, `ANO` C4, `MES` C2, `CNES` C7, `REMESSA` C21 | → `rejected`, `reason_code` = `CO_ERRO` (MOTERRO.DBF, 6 dígitos, ex.: 020001 AIH bloqueada por duplicidade) |
| `retorno_sia_csv` | `*sia*\|*sih*\|*bpa*\|*apac*\|*aih*.csv` | aliases | alternativa local — **A CONFIRMAR** |

Estrutura física completa observada nos arquivos oficiais (F4/F5), para referência:

- **PA** (60 campos, registro de 1 + soma): `PA_CODUNI C7, PA_GESTAO C6, PA_CONDIC C2, PA_UFMUN C6, PA_REGCT C4,
  PA_INCOUT C4, PA_INCURG C4, PA_TPUPS C2, PA_TIPPRE C2, PA_MN_IND C1, PA_CNPJCPF C14, PA_CNPJMNT C14,
  PA_CNPJ_CC C14, PA_MVM C6, PA_CMP C6, PA_PROC_ID C10, PA_TPFIN C2, PA_SUBFIN C4, PA_NIVCPL C1, PA_DOCORIG C1,
  PA_AUTORIZ C13, PA_CNSMED C15, PA_CBOCOD C6, PA_MOTSAI C2, PA_OBITO C1, PA_ENCERR C1, PA_PERMAN C1, PA_ALTA C1,
  PA_TRANSF C1, PA_CIDPRI C4, PA_CIDSEC C4, PA_CIDCAS C4, PA_CATEND C2, PA_IDADE C3, IDADEMIN C3, IDADEMAX C3,
  PA_FLIDADE C1, PA_SEXO C1, PA_RACACOR C2, PA_MUNPCN C6, PA_QTDPRO N11, PA_QTDAPR N11, PA_VALPRO N20.2,
  PA_VALAPR N20.2, PA_UFDIF C1, PA_MNDIF C1, PA_DIF_VAL N20.2, NU_VPA_TOT N20.2, NU_PA_TOT N20.2, PA_INDICA C1,
  PA_CODOCO C1, PA_FLQT C1, PA_FLER C1, PA_ETNIA C4, PA_VL_CF N20.2, PA_VL_CL N20.2, PA_VL_INC N20.2,
  PA_SRV_C C6, PA_INE C10, PA_NAT_JUR C4`.
- **RJ** = campos do RD de 2008+ até `ETNIA` + `ST_SITUAC C1, ST_BLOQ C1, ST_MOT_BLO C2, SEQUENCIA N9,
  REMESSA C21` (`N_AIH C13`, `ANO_CMPT C4`, `MES_CMPT C2`, `CNES C7`, `VAL_TOT N14.2`, …).
- **ER**: `SEQUENCIA N9, REMESSA C21, CNES C7, AIH C13, ANO C4, MES C2, DT_INTER C10, DT_SAIDA C10, MUN_MOV C6,
  UF_ZI C6, MUN_RES C6, UF_RES C2, CO_ERRO C6`.
- Códigos ainda **sem confirmação** de significado (não usados): `PA_DOCORIG` (valores observados B, C, I, P, S;
  sem CNV vinculado no `Producao_Ambulatorial.DEF`), `PA_CODOCO`.

## 6. Pendências

1. Conferir BPA (F1) e APAC (F2) com os PDFs originais — ou importar um arquivo gerado no BPA-Mag/APAC-Mag do
   município e comparar — antes da primeira transmissão; atualizar o grau neste documento.
2. Obter o layout SISAIH01 vigente (F3) e implementar a exportação de AIH.
3. Combinar com o faturamento a chave de conciliação dos retornos (nº AIH/APAC como `id_registro` da produção) e
   se os relatórios de crítica/glosa do SIA/SIHD serão exportados (alternativa CSV).
4. Raça/cor, nacionalidade e etnia: avaliar trazer do MPI para o arquivo (hoje `99`/branco).
