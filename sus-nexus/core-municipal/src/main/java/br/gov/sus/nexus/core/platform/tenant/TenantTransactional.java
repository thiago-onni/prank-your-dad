package br.gov.sus.nexus.core.platform.tenant;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Transação JTA (join existing) que executa {@code select set_config('app.tenant_id', ?, true)}
 * (equivalente a {@code SET LOCAL}) logo após o início, para que as políticas de RLS enxerguem o
 * tenant da requisição. Substitui {@code @Transactional} nos serviços de aplicação.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TenantTransactional {}
