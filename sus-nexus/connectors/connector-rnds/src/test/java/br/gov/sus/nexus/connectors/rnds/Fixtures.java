package br.gov.sus.nexus.connectors.rnds;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Fixtures FHIR (formato do fhir-gateway) e envelopes de evento do barramento. */
final class Fixtures {

  static final String EXR = "exr_01JM9S346Q3D25VT4F5V37E3S3";
  static final String DR_ID = "01JM9S346Q3D25VT4F5V37E3S3";
  static final String EXO = "exo_01JE28JT97KB6CQ643DZVMXXQK";
  static final String PATIENT_ID = "01JFBF5KZNWJ47TAN9ZT24MNPZ";
  static final String HEP = "hep_01JSSZ5AWSH8VHTPRE95B9EE0Z";
  static final String CNS = "700123456789010";
  static final String CPF = "12345678909";

  private Fixtures() {}

  static String read(String path) {
    try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalArgumentException("fixture ausente: " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** ULID válido (Crockford) e determinístico a partir de um número. */
  static String eventId(int n) {
    String digits = String.format("%026d", n);
    return "evt_" + digits;
  }

  static String examResultEvent(String eventId, String action, String status) {
    return """
        {
          "event_id": "%s",
          "event_type": "sus.exam.result.available",
          "event_version": "1.0",
          "occurred_at": "2026-09-30T17:00:00Z",
          "published_at": "2026-09-30T17:00:01Z",
          "tenant": {"municipality_id": "ibge_3143302"},
          "subject": {"municipal_citizen_id": "cit_01JFBF5KZNWJ47TAN9ZT24MNPZ",
                      "identifiers": [{"system": "CNS", "value_masked": "***********9010"}]},
          "source": {"system": "LIS", "connector": "connector-lis", "source_record_id": "LAB-123"},
          "data": {
            "action": "%s",
            "exam_order_id": "%s",
            "exam_result_id": "%s",
            "result_status": "%s",
            "critical": false,
            "reported_at": "2026-09-30T17:00:00Z",
            "performer_cnes": "1234567",
            "has_document": true,
            "observations_count": 2
          },
          "privacy": {"classification": "restricted"},
          "trace": {"correlation_id": "corr_teste"}
        }
        """
        .formatted(eventId, action, EXO, EXR, status);
  }

  static String dischargeEvent(String eventId) {
    return """
        {
          "event_id": "%s",
          "event_type": "sus.hospital.discharge.completed",
          "event_version": "1.0",
          "occurred_at": "2026-09-28T19:30:00Z",
          "published_at": "2026-09-28T19:30:01Z",
          "tenant": {"municipality_id": "ibge_3143302"},
          "source": {"system": "HIS", "connector": "connector-his", "source_record_id": "AT-9"},
          "data": {
            "action": "completed",
            "hospital_episode_id": "%s",
            "hospital_cnes": "7654321",
            "discharged_at": "2026-09-28T19:30:00Z",
            "disposition": "home",
            "principal_diagnosis": {"system": "CID10", "code": "I500"}
          },
          "privacy": {"classification": "restricted"},
          "trace": {"correlation_id": "corr_alta"}
        }
        """
        .formatted(eventId, HEP);
  }
}
