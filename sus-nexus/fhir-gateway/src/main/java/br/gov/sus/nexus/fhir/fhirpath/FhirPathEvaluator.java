package br.gov.sus.nexus.fhir.fhirpath;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.List;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.context.SimpleWorkerContext;
import org.hl7.fhir.r4.fhirpath.FHIRPathEngine;
import org.hl7.fhir.r4.model.Base;

/**
 * Avaliador FHIRPath (R4) sobre o modelo de objetos, usado para extração de índices de busca e
 * invariantes municipais. O {@link FHIRPathEngine} de referência não é thread-safe, por isso uma
 * instância por thread compartilhando um contexto vazio (não precisa de StructureDefinitions para
 * avaliar caminhos sobre objetos já tipados).
 */
@ApplicationScoped
public class FhirPathEvaluator {

  private final SimpleWorkerContext context;
  private final ThreadLocal<FHIRPathEngine> engines;

  public FhirPathEvaluator() {
    try {
      this.context = SimpleWorkerContext.fromNothing();
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao criar contexto FHIR R4", e);
    }
    this.engines = ThreadLocal.withInitial(() -> new FHIRPathEngine(context));
  }

  /** Avalia a expressão e devolve a coleção resultante (vazia quando não há correspondência). */
  public List<Base> evaluate(Base root, String expression) {
    try {
      return engines.get().evaluate(root, expression);
    } catch (FHIRException e) {
      throw new FhirPathException("Erro ao avaliar FHIRPath '" + expression + "'", e);
    }
  }

  /** Avalia a expressão como booleano (regras FHIRPath de conversão: vazio = false). */
  public boolean evaluateBoolean(Base root, String expression) {
    FHIRPathEngine engine = engines.get();
    try {
      return engine.convertToBoolean(engine.evaluate(root, expression));
    } catch (FHIRException e) {
      throw new FhirPathException("Erro ao avaliar FHIRPath '" + expression + "'", e);
    }
  }

  /** Erro de sintaxe/avaliação de uma expressão FHIRPath. */
  public static class FhirPathException extends RuntimeException {
    public FhirPathException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
