package br.gov.sus.nexus.core.terminology.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Código de terminologia versionado por competência. */
@Entity
@Table(schema = "terminology", name = "code")
public class Code {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false)
  public String system;

  @Column(nullable = false)
  public String code;

  @Column(nullable = false)
  public String display;

  @Column(name = "display_norm", insertable = false, updatable = false)
  public String displayNorm;

  @Column(name = "competence_from", nullable = false)
  public String competenceFrom;

  @Column(name = "competence_to")
  public String competenceTo;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public Map<String, Object> attributes;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  public Instant createdAt;
}
