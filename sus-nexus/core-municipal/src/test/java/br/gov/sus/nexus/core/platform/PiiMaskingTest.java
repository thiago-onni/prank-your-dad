package br.gov.sus.nexus.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.platform.logging.PiiLogFilter;
import br.gov.sus.nexus.core.platform.logging.PiiMasker;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.Test;

class PiiMaskingTest {

  @Test
  void masksCpfAndCnsInFreeText() {
    String text =
        "cidadão CPF 529.982.247-25 / 52998224725 e CNS 898001234565678 (tel 38999991234)";
    String masked = PiiMasker.mask(text);
    assertThat(masked)
        .doesNotContain("529.982.247-25")
        .doesNotContain("52998224725")
        .doesNotContain("898001234565678")
        .contains("***.***.***-25")
        .contains("***********5678");
  }

  @Test
  void keepsShortNumbersAndIds() {
    assertThat(PiiMasker.mask("cnes 1234567 cbo 225125 ano 2026"))
        .isEqualTo("cnes 1234567 cbo 225125 ano 2026");
    assertThat(PiiMasker.mask(null)).isNull();
  }

  @Test
  void logFilterRewritesMessageAndParameters() {
    List<String> captured = new ArrayList<>();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (!isLoggable(record)) {
              return;
            }
            captured.add(new SimpleFormatter().formatMessage(record));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    handler.setFilter(new PiiLogFilter());
    Logger logger = Logger.getLogger("pii-test");
    logger.setUseParentHandlers(false);
    logger.addHandler(handler);

    logger.info("registro CNS 898001234565678 recebido");
    logger.log(Level.INFO, "cpf {0} do cidadão", "52998224725");

    assertThat(captured).hasSize(2);
    assertThat(captured.get(0)).isEqualTo("registro CNS ***********5678 recebido");
    assertThat(captured.get(1)).isEqualTo("cpf ***.***.***-25 do cidadão");
  }
}
