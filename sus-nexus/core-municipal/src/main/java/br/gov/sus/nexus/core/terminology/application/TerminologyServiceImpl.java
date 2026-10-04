package br.gov.sus.nexus.core.terminology.application;

import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.sharedkernel.Competence;
import br.gov.sus.nexus.core.terminology.api.CodeDto;
import br.gov.sus.nexus.core.terminology.api.TerminologyService;
import br.gov.sus.nexus.core.terminology.domain.Code;
import br.gov.sus.nexus.core.terminology.infrastructure.CodeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Consulta e validação de códigos. Sem tenant: terminologia é compartilhada. */
@ApplicationScoped
public class TerminologyServiceImpl implements TerminologyService {

  public static final Set<String> SYSTEMS = Set.of("SIGTAP", "CID10", "CIAP2", "CBO");

  @Inject CodeRepository repository;

  @Override
  @Transactional
  public boolean isValid(String system, String code, String competence) {
    return find(system, code, competence).isPresent();
  }

  @Override
  @Transactional
  public Optional<CodeDto> find(String system, String code, String competence) {
    String sys = normalizeSystem(system);
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    String comp = normalizeCompetence(competence);
    return repository.findCurrent(sys, code.trim(), comp).map(TerminologyServiceImpl::toDto);
  }

  @Override
  @Transactional
  public Page<CodeDto> search(
      String system, String q, String code, String competence, String cursor, Integer limit) {
    String sys = normalizeSystem(system);
    String comp = normalizeCompetence(competence);
    int size = Cursor.limit(limit);
    Long afterId = Cursor.decode(cursor).map(Long::valueOf).orElse(null);
    List<Code> rows = repository.search(sys, q, code, comp, afterId, size + 1);
    List<CodeDto> dtos = rows.stream().map(TerminologyServiceImpl::toDto).toList();
    if (rows.size() <= size) {
      return new Page<>(dtos, null);
    }
    return new Page<>(dtos.subList(0, size), Cursor.encode(rows.get(size - 1).id.toString()));
  }

  static String normalizeSystem(String system) {
    String sys = system == null ? "" : system.trim().toUpperCase();
    if (!SYSTEMS.contains(sys)) {
      throw DomainValidationException.field("system", "sistema desconhecido: " + system);
    }
    return sys;
  }

  static String normalizeCompetence(String competence) {
    if (competence == null || competence.isBlank()) {
      return null;
    }
    return Competence.parse(competence.trim())
        .map(Competence::value)
        .orElseThrow(() -> DomainValidationException.field("competence", "formato AAAAMM"));
  }

  static CodeDto toDto(Code c) {
    return new CodeDto(c.system, c.code, c.display, c.competenceFrom, c.competenceTo, c.attributes);
  }
}
