package br.gov.sus.nexus.connectors.sdk.util;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.logging.PiiMaskingLogFilter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class PiiTest {

  @Test
  void mascaraCpfECns() {
    assertThat(Pii.maskCpf("123.456.789-09")).isEqualTo("***.***.***-09");
    assertThat(Pii.maskCns("898001234567890")).isEqualTo("***********7890");
    assertThat(Pii.maskText("cpf 123.456.789-09 cns 898001234567890 msg_01"))
        .isEqualTo("cpf ***.***.***-09 cns ***********7890 msg_01");
    assertThat(Pii.maskText("cpf 12345678909")).isEqualTo("cpf ***.***.***-09");
  }

  @Test
  void filtroDeLogMascaraMensagemEParametros() {
    PiiMaskingLogFilter filter = new PiiMaskingLogFilter();
    LogRecord record = new LogRecord(Level.INFO, "cidadão 123.456.789-09 recebido");
    record.setParameters(new Object[] {"898001234567890", 7});
    assertThat(filter.isLoggable(record)).isTrue();
    assertThat(record.getMessage()).isEqualTo("cidadão ***.***.***-09 recebido");
    assertThat(record.getParameters()[0]).isEqualTo("***********7890");
  }
}
