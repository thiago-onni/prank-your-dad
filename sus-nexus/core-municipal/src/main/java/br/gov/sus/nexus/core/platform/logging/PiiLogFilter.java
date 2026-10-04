package br.gov.sus.nexus.core.platform.logging;

import io.quarkus.logging.LoggingFilter;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

/**
 * Filtro JUL registrado em {@code quarkus.log.console.filter=pii-mask}: reescreve a mensagem e os
 * parâmetros do registro mascarando CPF/CNS antes da formatação. Nunca descarta registros.
 */
@LoggingFilter(name = "pii-mask")
public class PiiLogFilter implements Filter {

  @Override
  public boolean isLoggable(LogRecord record) {
    String msg = record.getMessage();
    if (msg != null) {
      record.setMessage(PiiMasker.mask(msg));
    }
    Object[] params = record.getParameters();
    if (params != null) {
      Object[] masked = new Object[params.length];
      for (int i = 0; i < params.length; i++) {
        masked[i] = params[i] instanceof String s ? PiiMasker.mask(s) : params[i];
      }
      record.setParameters(masked);
    }
    return true;
  }
}
