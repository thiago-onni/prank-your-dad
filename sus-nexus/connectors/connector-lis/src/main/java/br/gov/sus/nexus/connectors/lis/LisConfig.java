package br.gov.sus.nexus.connectors.lis;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/** Configuração do conector LIS (prefixo {@code lis.*}). */
@ConfigMapping(prefix = "lis")
public interface LisConfig {

  /** Nome do LIS/variante HL7 (informativo; descriptor e README). */
  @WithDefault("LIS genérico HL7 v2.3–2.5.1")
  String sourceVersion();

  /** Charset das mensagens HL7 (LIS brasileiros costumam usar ISO-8859-1). */
  @WithDefault("ISO-8859-1")
  String charset();

  /** Fuso para timestamps HL7 sem offset. */
  @WithDefault("America/Sao_Paulo")
  String zone();

  Mllp mllp();

  File file();

  Pid pid();

  Order order();

  Critical critical();

  Mapping mapping();

  interface Mllp {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("0.0.0.0")
    String host();

    @WithDefault("2575")
    int port();

    @WithDefault("30000")
    int receiveTimeoutMs();
  }

  interface File {
    @WithDefault("data/lis/in")
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

    /** Sistema do identificador local (PID-3 sem CNS/CPF) usado como último recurso. */
    @WithDefault("HIS")
    String localIdentifierSystem();
  }

  interface Order {
    /** Número que identifica o pedido no core: {@code placer} (ORC-2/OBR-2) ou {@code filler}. */
    @WithDefault("placer")
    String idSource();

    /** CNES solicitante quando ORC-21/ORC-17 não trazem um CNES de 7 dígitos. */
    Optional<String> defaultRequestingCnes();

    /** CNES do laboratório executante quando MSH-4 não é um CNES de 7 dígitos. */
    Optional<String> defaultPerformerCnes();

    @WithDefault("laboratory")
    String category();
  }

  interface Critical {
    /** Flags OBX-8 que marcam resultado crítico. */
    @WithDefault("HH,LL,AA")
    List<String> flags();

    /** Campo MSH (índice, ex.: 21) cujo valor marca a mensagem inteira como crítica. */
    Optional<Integer> mshField();

    @WithDefault("CRITICAL")
    String mshValue();
  }

  interface Mapping {
    @WithDefault("mappings/lis-exam-order-1.0.0.yaml")
    String order();

    @WithDefault("mappings/lis-exam-result-1.0.0.yaml")
    String result();
  }
}
