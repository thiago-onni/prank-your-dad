package br.gov.sus.nexus.core.terminology.application;

import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.ingestion.UpsertResult;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.sharedkernel.Competence;
import br.gov.sus.nexus.core.terminology.api.CodeDto;
import br.gov.sus.nexus.core.terminology.api.CodeUpsert;
import br.gov.sus.nexus.core.terminology.api.CodeUpsertBatch;
import br.gov.sus.nexus.core.terminology.api.TerminologyService;
import br.gov.sus.nexus.core.terminology.domain.Code;
import br.gov.sus.nexus.core.terminology.infrastructure.CodeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  @Override
  @Transactional
  public UpsertResult upsertBatch(String system, CodeUpsertBatch batch) {
    String sys = normalizeSystem(system);
    String batchCompetence = normalizeCompetence(batch.competence());
    UpsertResult.Counter counter = new UpsertResult.Counter();
    for (CodeUpsert item : batch.items()) {
      if (item == null
          || item.code() == null
          || item.code().isBlank()
          || item.display() == null
          || item.display().isBlank()) {
        counter.rejected();
        continue;
      }
      String from = item.competenceFrom() == null ? batchCompetence : item.competenceFrom();
      String to = item.competenceTo();
      if (from == null
          || Competence.parse(from).isEmpty()
          || (to != null && Competence.parse(to).isEmpty())) {
        counter.rejected();
        continue;
      }
      Optional<Code> existing = repository.findExact(sys, item.code().trim(), from);
      if (existing.isEmpty()) {
        Code c = new Code();
        c.system = sys;
        c.code = item.code().trim();
        c.display = item.display().trim();
        c.competenceFrom = from;
        c.competenceTo = to;
        c.attributes =
            item.attributes() == null ? Map.of() : new LinkedHashMap<>(item.attributes());
        repository.persist(c);
        counter.created();
        continue;
      }
      Code c = existing.get();
      boolean changed = false;
      if (!Objects.equals(c.display, item.display().trim())) {
        c.display = item.display().trim();
        changed = true;
      }
      if (to != null && !Objects.equals(c.competenceTo, to)) {
        c.competenceTo = to;
        changed = true;
      }
      if (item.attributes() != null && !item.attributes().isEmpty()) {
        Map<String, Object> merged =
            new LinkedHashMap<>(c.attributes == null ? Map.of() : c.attributes);
        merged.putAll(item.attributes());
        if (!merged.equals(c.attributes)) {
          c.attributes = merged;
          changed = true;
        }
      }
      if (changed) {
        counter.updated();
      } else {
        counter.unchanged();
      }
    }
    return counter.result();
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
