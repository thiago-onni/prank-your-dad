package br.gov.sus.nexus.core.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helpers de chamada à API em testes (@QuarkusTest, perfil test: auth por header). */
public final class Api {

  public static final String TENANT_A = "ibge_3143302";
  public static final String TENANT_B = "ibge_3106200";
  public static final String ROLES_INTEGRATION = "operador_integracao";
  public static final String ROLES_APS = "profissional_aps";
  public static final String ROLES_GESTOR = "gestor";
  public static final String ROLES_DPO = "dpo";

  private Api() {}

  public static RequestSpecification as(String tenant, String user, String roles) {
    return RestAssured.given()
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Test-User", user)
        .header("X-Test-Roles", roles)
        .header("X-Purpose-Of-Use", "care_coordination");
  }

  public static RequestSpecification integration(String tenant) {
    return as(tenant, "connector-pec", ROLES_INTEGRATION);
  }

  public static RequestSpecification aps(String tenant) {
    return as(tenant, "dra.ana", ROLES_APS);
  }

  public static RequestSpecification gestor(String tenant) {
    return as(tenant, "gestor.joao", ROLES_GESTOR);
  }

  public static RequestSpecification dpo(String tenant) {
    return as(tenant, "dpo.maria", ROLES_DPO);
  }

  /** Corpo de CitizenRegistration mínimo com builder fluente. */
  public static final class Registration {
    private final Map<String, Object> body = new LinkedHashMap<>();
    private final Map<String, Object> demographics = new LinkedHashMap<>();
    private final List<Map<String, String>> identifiers = new ArrayList<>();
    private final List<Map<String, String>> contacts = new ArrayList<>();

    public static Registration of(String legalName, LocalDate birthdate) {
      Registration r = new Registration();
      r.demographics.put("legal_name", legalName);
      r.demographics.put("birthdate", birthdate.toString());
      r.demographics.put("sex", "female");
      r.source("ESUS_APS_PEC", "connector-pec", "PEC-" + System.nanoTime());
      return r;
    }

    public Registration source(String system, String connector, String recordId) {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("system", system);
      source.put("connector", connector);
      source.put("source_record_id", recordId);
      source.put("source_record_version", "1");
      source.put("cnes", "1234567");
      body.put("source", source);
      return this;
    }

    public Registration mother(String motherName) {
      demographics.put("mother_name", motherName);
      return this;
    }

    public Registration sex(String sex) {
      demographics.put("sex", sex);
      return this;
    }

    public Registration social(String socialName) {
      demographics.put("social_name", socialName);
      return this;
    }

    public Registration cns(String cns) {
      identifiers.add(Map.of("system", "CNS", "value", cns));
      return this;
    }

    public Registration cpf(String cpf) {
      identifiers.add(Map.of("system", "CPF", "value", cpf));
      return this;
    }

    public Registration phone(String phone) {
      contacts.add(Map.of("kind", "mobile", "value", phone));
      return this;
    }

    public Registration territory(String cnes, String ine, String microarea) {
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("health_unit_cnes", cnes);
      t.put("team_ine", ine);
      t.put("microarea", microarea);
      body.put("territory", t);
      return this;
    }

    public Map<String, Object> build() {
      Map<String, Object> out = new LinkedHashMap<>(body);
      out.put("demographics", demographics);
      out.put("identifiers", identifiers);
      out.put("contacts", contacts);
      return out;
    }
  }

  /** Conexão administrativa (superusuário) para inspecionar tabelas em testes. */
  public static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://localhost:5432/sus_nexus_test", "postgres", "postgres");
  }
}
