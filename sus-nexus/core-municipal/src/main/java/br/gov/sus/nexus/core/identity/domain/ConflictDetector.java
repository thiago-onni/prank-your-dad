package br.gov.sus.nexus.core.identity.domain;

import br.gov.sus.nexus.core.identity.domain.MatchEvidence.Agreement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detecta divergências que impedem vínculo automático (MPI): mesmo CNS/CPF com data de nascimento
 * diferente, CNS/CPF divergentes entre os registros, ou identificador de entrada já pertencente a
 * outro cidadão. Um conflito NUNCA vincula — abre caso de revisão.
 */
public final class ConflictDetector {

  public static final String BIRTHDATE = "birthdate";
  public static final String CNS = "CNS";
  public static final String CPF = "CPF";

  private ConflictDetector() {}

  /** Resultado: conflitos (vazio = pode vincular) e evidências determinísticas. */
  public record Result(List<String> conflicts, List<MatchEvidence> evidences) {
    public boolean hasConflicts() {
      return !conflicts.isEmpty();
    }
  }

  /**
   * @param incoming registro de entrada
   * @param candidate cidadão candidato ao vínculo
   * @param incomingIdentifierOwners sistema → citizen_id dono ativo do identificador de entrada
   */
  public static Result detect(
      PersonFeatures incoming,
      PersonFeatures candidate,
      Map<String, String> incomingIdentifierOwners) {
    List<String> conflicts = new ArrayList<>();
    List<MatchEvidence> evidences = new ArrayList<>();

    for (String system : List.of(CNS, CPF)) {
      String a = incoming.identifierHash(system);
      String b = candidate.identifierHash(system);
      if (a != null && b != null) {
        if (a.equals(b)) {
          evidences.add(new MatchEvidence(system, "hash_equal", Agreement.AGREE, 0));
        } else {
          conflicts.add(system);
          evidences.add(new MatchEvidence(system, "hash_equal", Agreement.DISAGREE, 0));
        }
      } else if (a != null) {
        String owner = incomingIdentifierOwners.get(system);
        if (owner != null && !owner.equals(candidate.citizenId())) {
          conflicts.add(system + "_owned_by_other");
          evidences.add(new MatchEvidence(system, "owner", Agreement.DISAGREE, 0));
        } else {
          evidences.add(new MatchEvidence(system, "hash_equal", Agreement.MISSING, 0));
        }
      }
    }

    if (incoming.birthdate() != null && candidate.birthdate() != null) {
      if (Objects.equals(incoming.birthdate(), candidate.birthdate())) {
        evidences.add(new MatchEvidence(BIRTHDATE, "exact", Agreement.AGREE, 0));
      } else {
        conflicts.add(BIRTHDATE);
        evidences.add(new MatchEvidence(BIRTHDATE, "exact", Agreement.DISAGREE, 0));
      }
    }
    return new Result(List.copyOf(conflicts), List.copyOf(evidences));
  }
}
