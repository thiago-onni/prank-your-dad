# Keycloak — realm `sus-nexus` (dev)

Importado automaticamente pelo serviço `keycloak` (`start-dev --import-realm`). O arquivo
`realm-sus-nexus.json` é a fonte do realm de **desenvolvimento**; hml/prod usam o `KeycloakRealmImport`
do chart umbrella (`platform/helm/sus-nexus/templates/keycloak.yaml`) com overrides por ambiente.

## Clients

| clientId | Tipo | Uso |
|---|---|---|
| `web-shell` | confidential (BFF, Authorization Code + PKCE) | Next.js shell; secret `web-shell-dev-secret` |
| `core-municipal` | resource server + service account | valida JWT; `aud=core-municipal` |
| `fhir-gateway` | resource server + service account | escopos SMART (`patient/*.read`, `system/*.read`...) |
| `connector-<nome>` (`pec`, `agenda`, `sisreg`, `cnes`, `cadsus`, `his`, `lis`, `files`) | client credentials | conectores; papel `operador_integracao` |
| `ai-service` | client credentials + **token exchange** (`standard.token.exchange.enabled`) | agentes trocam o token do usuário por um token restrito (`agente_ia`) |
| `fhir-partner-example` | client credentials | modelo para parceiros externos (SMART backend services) |

Todos os secrets de dev seguem o padrão `<clientId>-dev-secret`.

## Papéis (realm roles)

`admin_municipal`, `gestor`, `profissional_aps`, `acs`, `regulador`, `agendador`, `profissional_hospitalar`,
`auditor`, `dpo`, `operador_integracao`, `cadastro_mestre`, `agente_ia`.

A autorização fina (ABAC) é feita pelo OPA (`sus-nexus/policies`) a partir das claims
`roles`, `municipality_id`, `cnes`, `teams`, `microareas`.

## Usuários de dev

Senha de todos: `sus-nexus-dev`. Atributo `municipality_id = ibge_3143302`, grupo `/ibge_3143302`.

| usuário | papéis |
|---|---|
| `admin.municipal` | admin_municipal, gestor |
| `gestor` | gestor |
| `prof.aps` | profissional_aps |
| `acs` | acs (microáreas 01, 02) |
| `regulador` | regulador |
| `agendador` | agendador |
| `prof.hospitalar` | profissional_hospitalar (cnes 2214733) |
| `auditor` | auditor |
| `dpo` | dpo |
| `operador.integracao` | operador_integracao |
| `cadastro.mestre` | cadastro_mestre |

Admin do Keycloak (master realm): `admin` / `admin` (variáveis `KEYCLOAK_ADMIN*` no `.env`).

## MFA — desabilitado SOMENTE em dev

> O plano (4.2.4) exige MFA (TOTP/WebAuthn) obrigatório. Em dev o fluxo `browser` padrão usa
> *Conditional OTP* e nenhum usuário tem a required action `CONFIGURE_TOTP`, portanto o login é
> apenas senha. Em hml/prod o `KeycloakRealmImport` adiciona `CONFIGURE_TOTP` como
> `defaultRequiredActions` e o executor OTP fica `REQUIRED` (ver `values-hml.yaml`/`values-prod.yaml`).
> JSON não aceita comentários: o lembrete também está em `attributes._dev_note_mfa` do realm.

## Regenerar

O JSON é gerado por script (ver histórico em `platform/README.md`); edite-o diretamente ou
exporte do Keycloak com `kc.sh export --realm sus-nexus --users realm_file`.
