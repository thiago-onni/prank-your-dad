package br.gov.sus.nexus.core.exams.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Resultado/laudo: metadados + referência segura; conteúdo nunca persistido ({@code
 * exams.exam_result}).
 */
@Entity
@Table(schema = "exams", name = "exam_result")
public class ExamResult {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "order_id", nullable = false)
  public String orderId;

  @Column(name = "reported_at", nullable = false)
  public Instant reportedAt;

  @Column(nullable = false)
  public String status;

  @Column(nullable = false)
  public boolean critical;

  @Column(name = "performer_cnes")
  public String performerCnes;

  @Column(name = "document_ref")
  public String documentRef;

  @Column(name = "document_content_type")
  public String documentContentType;

  @Column(name = "document_sha256")
  public String documentSha256;

  /** Observações codificadas (code, code_system, value, unit, abnormal) — sem texto livre. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public List<Map<String, Object>> observations = List.of();

  @Column(name = "observations_count", nullable = false)
  public int observationsCount;

  @Column(name = "followup_task_id")
  public String followupTaskId;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id")
  public String sourceRecordId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
