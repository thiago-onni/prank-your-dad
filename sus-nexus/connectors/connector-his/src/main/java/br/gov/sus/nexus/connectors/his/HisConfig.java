package br.gov.sus.nexus.connectors.his;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/** Configuração do conector HIS (prefixo {@code his.*}). */
@ConfigMapping(prefix = "his")
public interface HisConfig {

  /** Nome do HIS/variante HL7 (informativo; descriptor e README). */
  @WithDefault("HIS genérico HL7 v2.3–2.5.1 (ADT)")
  String sourceVersion();

  /**
   * Perfil de fornecedor: {@code generic}, {@code tasy}, {@code mv} ou {@code aghuse}. Só troca o
   * diretório dos YAML de mapeamento ({@code mappings/<vendor>/...}); a lógica Java é a mesma.
   */
  @WithDefault("generic")
  String vendor();

  /** Charset das mensagens HL7 (HIS brasileiros costumam usar ISO-8859-1). */
  @WithDefault("ISO-8859-1")
  String charset();

  /** Fuso para timestamps HL7 sem offset. */
  @WithDefault("America/Sao_Paulo")
  String zone();

  Mllp mllp();

  File file();

  Pid pid();

  Hospital hospital();

  Aih aih();

  Regulation regulation();

  Diagnosis diagnosis();

  Discharge discharge();

  Mapping mapping();

  interface Mllp {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("0.0.0.0")
    String host();

    @WithDefault("2576")
    int port();

    @WithDefault("30000")
    int receiveTimeoutMs();
  }

  interface File {
    @WithDefault("data/his/in")
    String inputDir();

    @WithDefault("5000")
    long pollDelayMs();
  }

  interface Pid {
    /** Código de tipo (CX-5) que identifica o CNS em PID-3. */
    @WithDefault("CNS")
    String cnsIdentifierType();

    /** Código de tipo (CX-5) que identifica o CPF em PID-3. */
    @WithDefault("CPF")
    String cpfIdentifierType();

    /** Sem tipo em CX-5, inferir pelo tamanho (15 dígitos = CNS, 11 = CPF). */
    @WithDefault("true")
    boolean inferByLength();

    /**
     * Sistema do identificador local (PID-3 com assigning authority do HIS) como último recurso.
     */
    @WithDefault("HIS")
    String localIdentifierSystem();
  }

  interface Hospital {
    /** CNES do hospital; quando ausente usa MSH-4 (7 dígitos) ou PV1-3-4. */
    Optional<String> cnes();

    /** Classe de episódio para ADT^A04 quando PV1-2 não for O (observação). */
    @WithDefault("E")
    String a04PatientClass();
  }

  interface Aih {
    /** Origem do nº da AIH: {@code none}, {@code pv1-50} (alternate visit id) ou {@code zai}. */
    @WithDefault("none")
    String source();

    /** Campo do segmento ZAI (ex.: {@code zai.2}) quando {@code source=zai}. */
    @WithDefault("zai.2")
    String zaiField();
  }

  interface Regulation {
    /** Origem do nº da solicitação de regulação: {@code none}, {@code pv1-5} ou um campo Z. */
    @WithDefault("pv1-5")
    String source();
  }

  interface Diagnosis {
    /** DG1-6 preferido para o diagnóstico principal da admissão (senão o primeiro DG1). */
    @WithDefault("A")
    String admitType();

    /** DG1-6 preferido para o diagnóstico principal da alta (senão o primeiro DG1). */
    @WithDefault("F")
    String dischargeType();
  }

  interface Discharge {
    /**
     * Quando o A03 carrega OBX/NTE (sumário), aponta {@code summary_document_ref} para a raw zone
     * (nunca o texto).
     */
    @WithDefault("true")
    boolean summaryRefFromRaw();
  }

  interface Mapping {
    @WithDefault("his-adt-1.0.0.yaml")
    String movement();

    @WithDefault("his-adt-discharge-1.0.0.yaml")
    String discharge();
  }
}
