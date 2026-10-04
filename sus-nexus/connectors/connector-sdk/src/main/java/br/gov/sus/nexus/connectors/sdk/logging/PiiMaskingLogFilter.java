package br.gov.sus.nexus.connectors.sdk.logging;

import br.gov.sus.nexus.connectors.sdk.util.Pii;
import io.quarkus.logging.LoggingFilter;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;

/**
 * Filtro de log que mascara CPF/CNS em qualquer mensagem. Ative com {@code
 * quarkus.log.console.filter=pii-mask} (e {@code quarkus.log.file.filter=pii-mask}).
 */
@LoggingFilter(name = "pii-mask")
public class PiiMaskingLogFilter implements Filter {

  @Override
  public boolean isLoggable(LogRecord record) {
    String msg = record.getMessage();
    if (msg != null) {
      String masked = Pii.maskText(msg);
      if (record instanceof ExtLogRecord ext) {
        ext.setMessage(masked, ext.getFormatStyle());
      } else {
        record.setMessage(masked);
      }
    }
    Object[] params = record.getParameters();
    if (params != null) {
      for (int i = 0; i < params.length; i++) {
        if (params[i] instanceof String s) params[i] = Pii.maskText(s);
      }
    }
    return true;
  }
}
