package br.gov.sus.nexus.connectors.ris;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/** Configuração do conector RIS/PACS (prefixo {@code ris.*}). */
@ConfigMapping(prefix = "ris")
public interface RisConfig {

  /** Nome do RIS/variante HL7 (informativo; descriptor e README). */
  @WithDefault("RIS genérico HL7 v2.3–2.5.1 + export DICOM")
  String sourceVersion();

  /** Charset das mensagens HL7. */
  @WithDefault("ISO-8859-1")
  String charset();

  /** Fuso para timestamps HL7/DICOM sem offset. */
  @WithDefault("America/Sao_Paulo")
  String zone();

  Mllp mllp();

  File file();

  Dicom dicom();

  Pid pid();

  Order order();

  Catalog catalog();

  Critical critical();

  Mapping mapping();

  interface Mllp {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("0.0.0.0")
    String host();

    @WithDefault("2577")
    int port();

    @WithDefault("30000")
    int receiveTimeoutMs();
  }

  interface File {
    @WithDefault("data/ris/in")
    String inputDir();

    @WithDefault("5000")
    long pollDelayMs();
  }

  /** Metadados DICOM exportados do PACS (JSON ou CSV): só referência ao estudo, nunca a imagem. */
  interface Dicom {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("data/ris/dicom")
    String inputDir();

    @WithDefault("10000")
    long pollDelayMs();

    /** AE Title do PACS usado em {@code document_ref = dicom://<AE>/<StudyInstanceUID>}. */
    @WithDefault("PACS")
    String aeTitle();

    /** Status do resultado publicado a partir dos metadados (estudo disponível no PACS). */
    @WithDefault("final")
    String status();

    /** Coluna/propriedade com o nº do pedido do RIS (target do by-source). */
    @WithDefault("AccessionNumber")
    String accessionField();

    /** Charset dos arquivos CSV exportados. */
    @WithDefault("UTF-8")
    String csvCharset();
  }

  interface Pid {
    @WithDefault("CNS")
    String cnsIdentifierType();

    @WithDefault("CPF")
    String cpfIdentifierType();

    @WithDefault("true")
    boolean inferByLength();

    @WithDefault("HIS")
    String localIdentifierSystem();
  }

  interface Order {
    /** Número que identifica o pedido no core: {@code placer} (ORC-2/OBR-2) ou {@code filler}. */
    @WithDefault("placer")
    String idSource();

    Optional<String> defaultRequestingCnes();

    /** CNES do serviço de imagem quando MSH-4 não é um CNES de 7 dígitos. */
    Optional<String> defaultPerformerCnes();

    @WithDefault("imaging")
    String category();
  }

  /** Catálogo código local do RIS → SIGTAP (YAML em classpath ou {@code file:/caminho}). */
  interface Catalog {
    @WithDefault("catalogs/ris-exam-catalog.yaml")
    String path();
  }

  interface Critical {
    /** Flags OBX-8 que marcam laudo crítico (achado crítico comunicado). */
    @WithDefault("HH,LL,AA,C")
    List<String> flags();

    /** Campo MSH (índice) cujo valor marca a mensagem como crítica. */
    Optional<Integer> mshField();

    @WithDefault("CRITICAL")
    String mshValue();

    /** Campo OBR (índice, ex.: 5 prioridade ou 13 informação clínica) com marcador de crítico. */
    Optional<Integer> obrField();

    @WithDefault("CRITICO")
    String obrValue();
  }

  interface Mapping {
    @WithDefault("mappings/ris-exam-order-1.0.0.yaml")
    String order();

    @WithDefault("mappings/ris-exam-result-1.0.0.yaml")
    String result();

    @WithDefault("mappings/ris-dicom-study-1.0.0.yaml")
    String dicom();
  }
}
