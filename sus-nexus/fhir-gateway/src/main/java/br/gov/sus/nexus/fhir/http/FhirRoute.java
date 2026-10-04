package br.gov.sus.nexus.fhir.http;

import br.gov.sus.nexus.fhir.capability.Interaction;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara qual interação do registro um método JAX-RS implementa. Um teste de arquitetura garante
 * que o conjunto de rotas anotadas coincide com as interações/operações do {@code
 * CapabilityRegistry}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FhirRoute {

  /** Interações de tipo/instância implementadas pela rota (vazio para operações). */
  Interaction[] value() default {};

  /** Nome da operação ({@code validate}, {@code everything}) quando a rota é uma operação. */
  String operation() default "";

  /**
   * Interações de sistema ({@code transaction}, {@code batch}, {@code history-system}) quando a
   * rota é de nível de sistema.
   */
  String[] system() default {};
}
