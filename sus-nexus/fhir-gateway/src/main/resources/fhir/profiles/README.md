# Perfis FHIR (br-core / RNDS)

Este diretório recebe os **pacotes NPM de Guias de Implementação** usados pelo validador oficial
(`OfficialProfileValidator`). Nenhum pacote é baixado em runtime: tudo é fixado no build (seção 6.4 do
plano).

## O que já está carregado

- **FHIR 4.0.1 base** (`StructureDefinition`, `ValueSet`, `CodeSystem`, extensões): vem do artefato
  Maven `ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r4` (somente recursos, sem código), convertido
  R4→R5 em memória pelo `R4ToR5Loader`. Isso substitui o download de `hl7.fhir.r4.core#4.0.1` do
  registro `packages.fhir.org`, indisponível neste ambiente.

## Como adicionar o pacote do br-core (ou RNDS)

1. Baixe o pacote NPM do IG em uma máquina com acesso, por exemplo
   `https://packages.fhir.org/br.core.saude.gov.br/<versão>` (nome exato conforme o IG publicado) ou o
   `package.tgz` disponibilizado na página do IG.
2. Copie o `.tgz` para este diretório, com o nome `<nome>-<versão>.tgz` (ex.:
   `br.core.saude.gov.br-1.0.0.tgz`). O arquivo entra no jar como recurso de classpath
   `fhir/profiles/<arquivo>`.
3. Registre-o em `application.properties`:

   ```properties
   sus.fhir.validation.ig-packages=fhir/profiles/br.core.saude.gov.br-1.0.0.tgz
   ```

   Vários pacotes podem ser separados por vírgula (dependências primeiro).
4. Confirme/ajuste os canônicos em `sus.fhir.profiles.*` (ex.:
   `https://br-core.saude.gov.br/fhir/StructureDefinition/BRCorePatient`). Esses valores são
   **parametrizados** porque o canônico do br-core pode variar entre versões do IG.
5. Rode `mvn -q verify`: com o pacote carregado, `ProfileValidator.knowsProfile(canônico)` passa a ser
   verdadeiro e os recursos com `meta.profile` são efetivamente verificados contra o perfil. O
   `CapabilityStatement` (`software.extension[validator-mode]`) exibe os pacotes carregados.

## Fixtures de teste

Exemplos oficiais do IG (pasta `package/example/` do `.tgz`) podem ser copiados para
`src/test/resources/fixtures/` e usados como fixtures. Enquanto não houver pacote disponível offline,
os fixtures em `src/test/resources/fixtures/` são conformes ao perfil conforme o conhecimento atual do
br-core (CNS/CPF como `identifier`, `meta.profile` declarado).

## Limites atuais

- Sem pacote br-core carregado, a conformidade ao perfil é garantida pelas regras locais
  (`MunicipalInvariants`) + estrutura/terminologia base; perfis declarados sob
  `sus.fhir.profiles.base-url` são aceitos sem verificação de slices/extensões do IG.
- Terminologias externas (CBO, CID-10, SIGTAP) não são expandidas (sem servidor de terminologia).
