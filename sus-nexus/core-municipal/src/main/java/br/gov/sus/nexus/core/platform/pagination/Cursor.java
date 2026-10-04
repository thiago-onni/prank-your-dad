package br.gov.sus.nexus.core.platform.pagination;

import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Cursor opaco (base64url) para paginação. O conteúdo é o último id da página (keyset). */
public final class Cursor {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  private Cursor() {}

  public static String encode(String lastKey) {
    if (lastKey == null) {
      return null;
    }
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(lastKey.getBytes(StandardCharsets.UTF_8));
  }

  public static Optional<String> decode(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.field("cursor", "cursor inválido");
    }
  }

  /** Normaliza o limite (1..200, padrão 50). */
  public static int limit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw DomainValidationException.field("limit", "deve estar entre 1 e " + MAX_LIMIT);
    }
    return limit;
  }
}
