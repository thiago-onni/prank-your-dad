package br.gov.sus.nexus.core.audit.api;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registra {@code audit.access_log} para leituras de dado de cidadão: quem, o quê, finalidade
 * ({@code X-Purpose-Of-Use}), decisão e correlation id. O id do cidadão é lido do parâmetro
 * {@code @PathParam(citizenIdParam)} do método, quando existir.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AuditedAccess {

  /** Tipo do recurso (ex.: {@code citizen}, {@code citizen_search}, {@code merge_case}). */
  @Nonbinding
  String resourceType() default "citizen";

  /** Ação (ex.: {@code read}, {@code search}, {@code reveal_identifier}). */
  @Nonbinding
  String action() default "read";

  /** Nome do path param que contém o id do cidadão. */
  @Nonbinding
  String citizenIdParam() default "citizenId";

  /** Exige {@code X-Purpose-Of-Use}; sem ele a chamada é negada (400) e registrada como deny. */
  @Nonbinding
  boolean requirePurpose() default true;
}
