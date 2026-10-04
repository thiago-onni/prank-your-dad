package br.gov.sus.nexus.fhir.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Escopo SMART-like: {@code <context>/<Tipo|*>.<read|write|*|cruds>}.
 *
 * <p>Semântica adotada no gateway:
 *
 * <ul>
 *   <li>{@code read} = r+s (leitura completa), {@code write} = c+u+d, {@code *} = tudo;
 *   <li>letras granulares (v2, ex.: {@code rs}) são tratadas como <b>leitura restrita</b>: concedem
 *       acesso, mas políticas de redação podem remover dados de contato ({@link
 *       ScopeRedactionPolicy}).
 * </ul>
 *
 * @param context {@code patient}, {@code user} ou {@code system}
 * @param resourceType tipo FHIR ou {@code *}
 * @param permissions permissões concedidas
 * @param granular {@code true} quando expresso em letras v2 (leitura restrita)
 */
public record SmartScope(
    String context, String resourceType, Set<Permission> permissions, boolean granular) {

  private static final Pattern PATTERN =
      Pattern.compile(
          "^(patient|user|system)/(\\*|[A-Z][A-Za-z]+)\\.(read|write|\\*|[cruds]{1,5})$");

  public static Optional<SmartScope> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    Matcher m = PATTERN.matcher(raw.trim());
    if (!m.matches()) {
      return Optional.empty();
    }
    String verb = m.group(3);
    EnumSet<Permission> perms = EnumSet.noneOf(Permission.class);
    boolean granular = false;
    switch (verb) {
      case "read" -> perms.addAll(EnumSet.of(Permission.READ, Permission.SEARCH));
      case "write" ->
          perms.addAll(EnumSet.of(Permission.CREATE, Permission.UPDATE, Permission.DELETE));
      case "*" -> perms.addAll(EnumSet.allOf(Permission.class));
      default -> {
        granular = true;
        for (char c : verb.toCharArray()) {
          perms.add(Permission.ofLetter(c));
        }
      }
    }
    return Optional.of(
        new SmartScope(m.group(1), m.group(2), Collections.unmodifiableSet(perms), granular));
  }

  public boolean coversType(String type) {
    return "*".equals(resourceType) || resourceType.equals(type);
  }

  public boolean grants(String type, Permission permission) {
    return coversType(type) && permissions.contains(permission);
  }

  public boolean isPatientContext() {
    return "patient".equals(context);
  }

  public boolean isSystemContext() {
    return "system".equals(context);
  }
}
