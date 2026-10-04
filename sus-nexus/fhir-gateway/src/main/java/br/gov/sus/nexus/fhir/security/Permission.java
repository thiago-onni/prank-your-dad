package br.gov.sus.nexus.fhir.security;

/** Permissões SMART v2 (c/r/u/d/s). */
public enum Permission {
  CREATE('c'),
  READ('r'),
  UPDATE('u'),
  DELETE('d'),
  SEARCH('s');

  private final char letter;

  Permission(char letter) {
    this.letter = letter;
  }

  public char letter() {
    return letter;
  }

  static Permission ofLetter(char c) {
    for (Permission p : values()) {
      if (p.letter == c) {
        return p;
      }
    }
    throw new IllegalArgumentException("Permissão desconhecida: " + c);
  }
}
