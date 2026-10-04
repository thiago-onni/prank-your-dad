package br.gov.sus.nexus.core.identity;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.TENANT_B;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.dpo;
import static br.gov.sus.nexus.core.support.Api.gestor;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fluxo do MPI de ponta a ponta contra o PostgreSQL local (RLS ativo, papel sem bypass). */
@QuarkusTest
class IdentityFlowTest {

  @Test
  void registerNewCitizenReturns201WithValidatedState() {
    String cns = Fixtures.randomProvisionalCns();
    Response r =
        integration(TENANT_A)
            .body(
                Registration.of("Maria das Dores Oliveira", LocalDate.of(1985, 3, 10))
                    .mother("Josefa Oliveira")
                    .cns(cns)
                    .phone("(38) 99999-0001")
                    .territory("1234567", "0000123456", "03")
                    .build())
            .post("/api/v1/citizens");
    r.then()
        .statusCode(201)
        .body("classification", equalTo("new"))
        .body("method", equalTo("none"))
        .body("registration_state", equalTo("validated"))
        .body("municipal_citizen_id", startsWith("cit_"))
        .body("merge_case_id", nullValue());

    String id = r.path("municipal_citizen_id");
    aps(TENANT_A)
        .get("/api/v1/citizens/" + id)
        .then()
        .statusCode(200)
        .header("ETag", notNullValue())
        .body("display_name", equalTo("Maria das Dores Oliveira"))
        .body("identifiers", hasSize(1))
        .body("identifiers[0].system", equalTo("CNS"))
        .body("identifiers[0].value_masked", equalTo("***********" + cns.substring(11)))
        .body("identifiers[0].status", equalTo("active"))
        .body("contacts[0].value_masked", equalTo("*******0001"))
        .body("attribute_provenance.legal_name.source_system", equalTo("ESUS_APS_PEC"))
        .body("health_unit_cnes", equalTo("1234567"));
  }

  @Test
  void sameCnsFromAnotherSourceIsLinkedWith200() {
    String cns = Fixtures.randomDefinitiveCns();
    String first =
        integration(TENANT_A)
            .body(
                Registration.of("Carlos Alberto Souza", LocalDate.of(1970, 7, 1))
                    .sex("male")
                    .cns(cns)
                    .build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");

    Response second =
        integration(TENANT_A)
            .body(
                Registration.of("CARLOS A. SOUZA", LocalDate.of(1970, 7, 1))
                    .sex("male")
                    .source("SISREG", "connector-sisreg", "SISREG-" + System.nanoTime())
                    .mother("Benedita Souza")
                    .cns(cns)
                    .build())
            .post("/api/v1/citizens");
    second
        .then()
        .statusCode(200)
        .body("classification", equalTo("confirmed"))
        .body("method", equalTo("deterministic_cns"))
        .body("municipal_citizen_id", equalTo(first));

    // sobrevivência: nome da mãe preenchido a partir da segunda origem, proveniência registrada
    aps(TENANT_A)
        .get("/api/v1/citizens/" + first)
        .then()
        .statusCode(200)
        .body("mother_name", equalTo("Benedita Souza"))
        .body("attribute_provenance.mother_name.source_system", equalTo("SISREG"))
        .body("legal_name", equalTo("Carlos Alberto Souza"));

    // terceira vez pelo mesmo registro de origem: idempotente (source_link)
    integration(TENANT_A)
        .body(
            Registration.of("Carlos Alberto Souza", LocalDate.of(1970, 7, 1))
                .sex("male")
                .source("SISREG", "connector-sisreg", second.path("municipal_citizen_id") + "-x")
                .build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(202); // sem CNS e sem mãe → não cai na regra d; probabilístico abre caso
  }

  @Test
  void sameCnsDifferentBirthdateOpensConflictCaseThenMergeAndUnmerge() {
    String cns = Fixtures.randomProvisionalCns();
    String first =
        integration(TENANT_A)
            .body(
                Registration.of("Joana Pereira Lima", LocalDate.of(1980, 1, 1))
                    .mother("Rita Lima")
                    .cns(cns)
                    .build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");

    Response conflict =
        integration(TENANT_A)
            .body(
                Registration.of("Joana Pereira Lima", LocalDate.of(1981, 2, 2))
                    .mother("Rita Lima")
                    .cns(cns)
                    .build())
            .post("/api/v1/citizens");
    conflict
        .then()
        .statusCode(202)
        .body("classification", equalTo("pending"))
        .body("method", equalTo("deterministic_cns"))
        .body("registration_state", equalTo("divergent"))
        .body("merge_case_id", startsWith("case_"));
    String second = conflict.path("municipal_citizen_id");
    String caseId = conflict.path("merge_case_id");
    assertThat(second).isNotEqualTo(first);

    gestor(TENANT_A)
        .get("/api/v1/mpi/cases/" + caseId)
        .then()
        .statusCode(200)
        .body("status", equalTo("open"))
        .body("conflicts", hasItem("birthdate"))
        .body("candidates", hasSize(2))
        .body("evidence.attribute", hasItem("CNS"))
        .body("evidence.attribute", hasItem("birthdate"));

    // o CNS conflitante fica 'invalid' no registro divergente (nunca ativo em dois cidadãos)
    aps(TENANT_A)
        .get("/api/v1/citizens/" + second)
        .then()
        .statusCode(200)
        .body("identifiers[0].status", equalTo("invalid"))
        .body("data_quality_issues.rule", hasItem("identifier.invalid_or_conflicting"));

    gestor(TENANT_A)
        .get("/api/v1/mpi/cases?status=open")
        .then()
        .statusCode(200)
        .body("items.id", hasItem(caseId));

    // merge exige justificativa (>= 10 chars)
    gestor(TENANT_A)
        .body(Map.of("surviving_citizen_id", first, "reason", "curto"))
        .post("/api/v1/mpi/cases/" + caseId + "/merge")
        .then()
        .statusCode(400)
        .contentType("application/problem+json");

    Response merged =
        gestor(TENANT_A)
            .body(
                Map.of(
                    "surviving_citizen_id",
                    first,
                    "reason",
                    "Mesma pessoa; data de nascimento digitada errada na origem"))
            .post("/api/v1/mpi/cases/" + caseId + "/merge");
    merged
        .then()
        .statusCode(200)
        .body("status", equalTo("merged"))
        .body("merge_id", startsWith("merge_"))
        .body("decided_by", equalTo("gestor.joao"));
    String mergeId = merged.path("merge_id");

    aps(TENANT_A)
        .get("/api/v1/citizens/" + second)
        .then()
        .statusCode(200)
        .body("registration_state", equalTo("duplicate"))
        .body("merged_into_id", equalTo(first));

    // busca por CNS continua encontrando ambos (merge não apaga)
    aps(TENANT_A)
        .get("/api/v1/citizens?identifier=CNS|" + cns)
        .then()
        .statusCode(200)
        .body("items.id", hasItem(first))
        .body("items.id", hasItem(second));

    gestor(TENANT_A)
        .body(Map.of("reason", "Revisão posterior mostrou que são pessoas distintas"))
        .post("/api/v1/mpi/merges/" + mergeId + "/unmerge")
        .then()
        .statusCode(200)
        .body("status", equalTo("unmerged"));

    aps(TENANT_A)
        .get("/api/v1/citizens/" + second)
        .then()
        .statusCode(200)
        .body("registration_state", equalTo("divergent"))
        .body("merged_into_id", nullValue());

    gestor(TENANT_A)
        .body(Map.of("reason", "Não é possível reverter duas vezes"))
        .post("/api/v1/mpi/merges/" + mergeId + "/unmerge")
        .then()
        .statusCode(409);
  }

  @Test
  void similarDemographicsWithoutCnsOpensReviewCaseWithoutLinking() {
    String first =
        integration(TENANT_A)
            .body(
                Registration.of("Maria Aparecida da Silva", LocalDate.of(1990, 5, 5))
                    .mother("Joana da Silva")
                    .phone("38 98888-7777")
                    .build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");

    Response r =
        integration(TENANT_A)
            .body(
                Registration.of("Maria Aparecida da Silveira", LocalDate.of(1990, 5, 5))
                    .mother("Joanna Silva")
                    .source("HIS", "connector-his", "HIS-" + System.nanoTime())
                    .build())
            .post("/api/v1/citizens");
    r.then()
        .statusCode(202)
        .body("method", equalTo("probabilistic"))
        .body("score", greaterThanOrEqualTo(12.0f))
        .body("registration_state", equalTo("pending"))
        .body("merge_case_id", startsWith("case_"));
    String second = r.path("municipal_citizen_id");
    String classification = r.path("classification");
    assertThat(classification).isIn("probable", "pending");
    assertThat(second).as("nunca vincula automaticamente").isNotEqualTo(first);

    gestor(TENANT_A)
        .get("/api/v1/mpi/cases/" + r.path("merge_case_id"))
        .then()
        .statusCode(200)
        .body("status", equalTo("open"))
        .body("candidates.id", hasItem(first))
        .body("candidates.id", hasItem(second))
        .body("evidence.attribute", hasItem("name"))
        .body("evidence.attribute", hasItem("mother_name"))
        .body("evidence.attribute", hasItem("birthdate"))
        .body("rule_version", equalTo("mpi-rules-1.0"));

    // rejeitar: não são a mesma pessoa → registro pendente vira validado
    gestor(TENANT_A)
        .body(Map.of("reason", "Pessoas diferentes confirmadas pela unidade"))
        .post("/api/v1/mpi/cases/" + r.path("merge_case_id") + "/reject")
        .then()
        .statusCode(200)
        .body("status", equalTo("rejected"));
    aps(TENANT_A)
        .get("/api/v1/citizens/" + second)
        .then()
        .statusCode(200)
        .body("registration_state", equalTo("validated"))
        .body("identity_confidence", equalTo("confirmed"));
  }

  @Test
  void textSearchUsesTrigramOverNormalizedNames() {
    integration(TENANT_A)
        .body(
            Registration.of("Antônio Conceição Fagundes", LocalDate.of(1960, 12, 25))
                .sex("male")
                .mother("Zélia Fagundes")
                .build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(201);
    aps(TENANT_A)
        .get("/api/v1/citizens?q=antonio fagundes")
        .then()
        .statusCode(200)
        .body("items.display_name", hasItem("Antônio Conceição Fagundes"))
        .body("items[0].mother_name_masked", equalTo("Zélia F."));
    aps(TENANT_A)
        .get("/api/v1/citizens?q=zelia fagundes&birthdate=1960-12-25")
        .then()
        .statusCode(200)
        .body("items", hasSize(greaterThanOrEqualTo(1)));
  }

  @Test
  void tenantIsolationIsEnforcedByRowLevelSecurity() {
    String cns = Fixtures.randomProvisionalCns();
    String id =
        integration(TENANT_A)
            .body(Registration.of("Isolada Tenant A", LocalDate.of(2000, 1, 1)).cns(cns).build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");

    aps(TENANT_B).get("/api/v1/citizens/" + id).then().statusCode(404);
    aps(TENANT_B)
        .get("/api/v1/citizens?identifier=CNS|" + cns)
        .then()
        .statusCode(200)
        .body("items", hasSize(0));
    aps(TENANT_A)
        .get("/api/v1/citizens?identifier=CNS|" + cns)
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].id", equalTo(id));

    // o mesmo CNS em outro município é um cidadão novo daquele tenant (chave HMAC por tenant)
    integration(TENANT_B)
        .body(Registration.of("Isolada Tenant B", LocalDate.of(2000, 1, 1)).cns(cns).build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(201)
        .body("classification", equalTo("new"));

    // sem tenant → 400 problem; sem autenticação → 401
    io.restassured.RestAssured.given()
        .header("X-Test-User", "x")
        .header("X-Test-Roles", "profissional_aps")
        .header("X-Purpose-Of-Use", "care_coordination")
        .get("/api/v1/citizens/" + id)
        .then()
        .statusCode(400)
        .contentType("application/problem+json")
        .body("type", equalTo("urn:sus-nexus:problem:tenant-required"));
    io.restassured.RestAssured.given()
        .header("X-Tenant-Id", TENANT_A)
        .get("/api/v1/citizens/" + id)
        .then()
        .statusCode(401);
    Api.as(TENANT_A, "acs.pedro", "acs")
        .get("/api/v1/audit/access")
        .then()
        .statusCode(403)
        .contentType("application/problem+json");
  }

  @Test
  @SuppressWarnings("unchecked")
  void getCitizenGeneratesAccessLogAndRequiresPurpose() {
    String id =
        integration(TENANT_A)
            .body(Registration.of("Acesso Auditado", LocalDate.of(1999, 9, 9)).build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");

    aps(TENANT_A).get("/api/v1/citizens/" + id).then().statusCode(200);

    // sem finalidade: negado e registrado como deny
    io.restassured.RestAssured.given()
        .header("X-Tenant-Id", TENANT_A)
        .header("X-Test-User", "dra.ana")
        .header("X-Test-Roles", "profissional_aps")
        .get("/api/v1/citizens/" + id)
        .then()
        .statusCode(400)
        .body("type", equalTo("urn:sus-nexus:problem:purpose-required"));

    Response log = dpo(TENANT_A).get("/api/v1/audit/access?citizen_id=" + id);
    log.then().statusCode(200);
    List<Map<String, Object>> items = log.path("items");
    assertThat(items).hasSizeGreaterThanOrEqualTo(2);
    assertThat(items)
        .anySatisfy(
            e -> {
              assertThat(e.get("action")).isEqualTo("read");
              assertThat(e.get("decision")).isEqualTo("allow");
              assertThat(e.get("purpose")).isEqualTo("care_coordination");
              assertThat(e.get("actor_id")).isEqualTo("dra.ana");
              assertThat((List<String>) e.get("actor_roles")).contains("profissional_aps");
              assertThat(e.get("correlation_id")).isNotNull();
            });
    assertThat(items).anySatisfy(e -> assertThat(e.get("decision")).isEqualTo("deny"));

    // access_log de outro tenant não vaza
    dpo(TENANT_B)
        .get("/api/v1/audit/access?citizen_id=" + id)
        .then()
        .statusCode(200)
        .body("items", hasSize(0));
  }

  @Test
  void revealReturnsPlainIdentifierAndLogsAccess() {
    String cpf = Fixtures.randomCpf();
    String id =
        integration(TENANT_A)
            .body(Registration.of("Revelada Silva", LocalDate.of(1988, 8, 8)).cpf(cpf).build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");
    String identifierId =
        aps(TENANT_A).get("/api/v1/citizens/" + id).then().extract().path("identifiers[0].id");

    aps(TENANT_A)
        .body(
            Map.of(
                "purpose",
                "care_coordination",
                "justification",
                "Conferência de cadastro no atendimento"))
        .post("/api/v1/citizens/" + id + "/identifiers/" + identifierId + "/reveal")
        .then()
        .statusCode(200)
        .body("system", equalTo("CPF"))
        .body("value", equalTo(cpf));

    // ACS não pode revelar (papel insuficiente)
    Api.as(TENANT_A, "acs.pedro", "acs")
        .body(
            Map.of("purpose", "care_coordination", "justification", "Tentativa indevida de acesso"))
        .post("/api/v1/citizens/" + id + "/identifiers/" + identifierId + "/reveal")
        .then()
        .statusCode(403);

    dpo(TENANT_A)
        .get("/api/v1/audit/access?citizen_id=" + id + "&actor_id=dra.ana")
        .then()
        .statusCode(200)
        .body("items.action", hasItem("reveal_identifier"))
        .body("items.find { it.action == 'reveal_identifier' }.resource_id", is(identifierId));
  }
}
