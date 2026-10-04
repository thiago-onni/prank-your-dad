package br.gov.sus.nexus.connectors.rnds;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Element;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Montagem (pura, sem I/O) do {@link Bundle} de um modelo RNDS a partir dos recursos do
 * fhir-gateway, aplicando o {@link ModelMapping}: perfis em {@code meta.profile}, referências
 * lógicas por CNS/CPF/CNES, remoção de extensões/identificadores/elementos locais e {@code fullUrl}
 * {@code urn:uuid} determinísticos (mesmo evento → mesmo Bundle, exceto {@code timestamp}).
 */
public final class BundleAssembler {

  /** Parâmetros do envio (não vêm do FHIR). */
  public record Context(
      String eventId,
      String bundleType,
      String solicitanteId,
      String cnesSolicitante,
      Instant now) {}

  public static final String RESULTADO_EXAME = "resultado-exame";
  public static final String SUMARIO_ALTA = "sumario-alta";

  private BundleAssembler() {}

  public static AssembledBundle assemble(ModelMapping mapping, SourceResources src, Context ctx) {
    return switch (mapping.model()) {
      case RESULTADO_EXAME -> new Run(mapping, src, ctx).examResult();
      case SUMARIO_ALTA -> new Run(mapping, src, ctx).discharge();
      default -> throw new IllegalArgumentException("modelo RNDS sem montador: " + mapping.model());
    };
  }

  /** Estado de uma montagem. */
  private static final class Run {
    private final ModelMapping mapping;
    private final SourceResources src;
    private final Context ctx;
    private final Map<String, String> facts = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> urnByLiteral = new HashMap<>();
    private final Map<String, Reference> logicalByLiteral = new HashMap<>();
    private final Bundle bundle = new Bundle();

    Run(ModelMapping mapping, SourceResources src, Context ctx) {
      this.mapping = mapping;
      this.src = src;
      this.ctx = ctx;
    }

    // ------------------------------------------------------------------ resultado de exame

    AssembledBundle examResult() {
      DiagnosticReport source = src.report();
      if (source == null) throw new IllegalArgumentException("DiagnosticReport ausente");
      Reference patient = patientReference();

      DiagnosticReport report = source.copy();
      String reportUrn = urn("DiagnosticReport/" + source.getIdElement().getIdPart());
      fact("report.status", source.getStatus() == null ? null : source.getStatus().toCode());
      CodeableConcept code = source.hasCode() ? source.getCode() : null;
      if ((code == null || !code.hasCoding()) && src.serviceRequest() != null) {
        code = src.serviceRequest().getCode();
        report.setCode(code.copy());
      }
      if (code != null && code.hasCoding()) {
        fact("exam.code", code.getCodingFirstRep().getCode());
        fact("exam.code_system", code.getCodingFirstRep().getSystem());
      }
      if (source.hasEffectiveDateTimeType()) {
        fact("exam.date", source.getEffectiveDateTimeType().getValueAsString());
      } else if (source.hasIssued()) {
        fact("exam.date", new DateTimeType(source.getIssued()).getValueAsString());
      }
      fact("observations.count", String.valueOf(src.observations().size()));

      // Organization executante (CNES): referência lógica
      String performerCnes = null;
      report.getPerformer().clear();
      for (Reference ref : source.getPerformer()) {
        String cnes = cnesOf(ref);
        if (cnes != null) {
          performerCnes = performerCnes == null ? cnes : performerCnes;
          report.addPerformer(cnesReference(cnes));
        }
      }
      if (performerCnes == null) performerCnes = src.eventData().get("performer_cnes");
      if (performerCnes != null && !report.hasPerformer()) {
        report.addPerformer(cnesReference(performerCnes));
      }
      fact("performer.cnes", performerCnes);

      // Profissional (laudo): referência lógica por CNS/CPF
      report.getResultsInterpreter().clear();
      for (Practitioner p : src.practitioners()) {
        practitionerReference(p).ifPresent(report::addResultsInterpreter);
      }

      report.setSubject(patient.copy());
      List<Resource> entries = new ArrayList<>();
      List<Observation> observations = new ArrayList<>();
      for (Observation o : src.observations()) {
        String literal = "Observation/" + o.getIdElement().getIdPart();
        urn(literal);
        Observation copy = o.copy();
        copy.setSubject(patient.copy());
        List<Reference> performers = new ArrayList<>();
        for (Reference ref : o.getPerformer()) {
          String cnes = cnesOf(ref);
          if (cnes != null) performers.add(cnesReference(cnes));
        }
        copy.getPerformer().clear();
        performers.forEach(copy::addPerformer);
        observations.add(copy);
      }
      prepare(report, "DiagnosticReport");
      entries.add(report);
      if (!"omit".equals(mapping.resource("Observation").modeOrDefault())) {
        for (Observation o : observations) {
          prepare(o, "Observation");
          entries.add(o);
        }
      } else {
        report.getResult().clear();
      }
      if ("entry".equals(mapping.resource("Patient").modeOrDefault()) && src.patient() != null) {
        Patient p = minimalPatient(src.patient());
        entries.add(p);
      }
      String authorCnes = ctx.cnesSolicitante() != null ? ctx.cnesSolicitante() : performerCnes;
      Date docDate = source.hasIssued() ? source.getIssued() : Date.from(ctx.now());
      return finish(entries, reportUrn, patient, authorCnes, docDate, null, versioned(source));
    }

    // ------------------------------------------------------------------ sumário de alta

    AssembledBundle discharge() {
      Encounter source = src.encounter();
      if (source == null) throw new IllegalArgumentException("Encounter ausente");
      Reference patient = patientReference();
      Encounter encounter = source.copy();
      String encounterUrn = urn("Encounter/" + source.getIdElement().getIdPart());
      fact("encounter.status", source.getStatus() == null ? null : source.getStatus().toCode());
      if (source.hasPeriod()) {
        if (source.getPeriod().hasStart()) {
          fact("encounter.start", source.getPeriod().getStartElement().getValueAsString());
        }
        if (source.getPeriod().hasEnd()) {
          fact("encounter.end", source.getPeriod().getEndElement().getValueAsString());
        }
      }
      String hospitalCnes =
          source.hasServiceProvider() ? cnesOf(source.getServiceProvider()) : null;
      if (hospitalCnes == null) hospitalCnes = src.eventData().get("hospital_cnes");
      fact("hospital.cnes", hospitalCnes);
      encounter.setServiceProvider(hospitalCnes == null ? null : cnesReference(hospitalCnes));
      encounter.setSubject(patient.copy());
      encounter.getParticipant().clear();
      encounter.getDiagnosis().clear();

      List<Resource> entries = new ArrayList<>();
      entries.add(encounter);
      String cid = src.eventData().get("principal_diagnosis_code");
      if (cid == null) {
        cid =
            source.getReasonCode().stream()
                .flatMap(cc -> cc.getCoding().stream())
                .filter(c -> c.getSystem() != null && c.getSystem().contains("icd-10"))
                .map(Coding::getCode)
                .findFirst()
                .orElse(null);
      }
      fact("diagnosis.code", cid);
      if (cid != null && !"omit".equals(mapping.resource("Condition").modeOrDefault())) {
        Condition condition = new Condition();
        condition.setId("principal-" + src.stableId());
        String conditionUrn = urn("Condition/principal-" + src.stableId());
        condition.setSubject(patient.copy());
        condition.setEncounter(new Reference(encounterUrn));
        condition
            .getCode()
            .addCoding()
            .setSystem(
                mapping.codeSystems().getOrDefault("cid10", "http://hl7.org/fhir/sid/icd-10"))
            .setCode(cid);
        prepare(condition, "Condition");
        entries.add(condition);
        encounter.addDiagnosis().setCondition(new Reference(conditionUrn)).setRank(1);
      }
      prepare(encounter, "Encounter");
      if ("entry".equals(mapping.resource("Patient").modeOrDefault()) && src.patient() != null) {
        entries.add(minimalPatient(src.patient()));
      }
      String authorCnes = ctx.cnesSolicitante() != null ? ctx.cnesSolicitante() : hospitalCnes;
      Date docDate =
          source.hasPeriod() && source.getPeriod().hasEnd()
              ? source.getPeriod().getEnd()
              : Date.from(ctx.now());
      return finish(
          entries, encounterUrn, patient, authorCnes, docDate, encounterUrn, versioned(source));
    }

    // ------------------------------------------------------------------ comum

    private AssembledBundle finish(
        List<Resource> entries,
        String mainUrn,
        Reference patient,
        String authorCnes,
        Date docDate,
        String encounterUrn,
        String identifierValue) {
      bundle.setType(Bundle.BundleType.fromCode(ctx.bundleType()));
      if (mapping.bundle().profile() != null)
        bundle.getMeta().addProfile(mapping.bundle().profile());
      String solicitante =
          ctx.solicitanteId() != null
              ? ctx.solicitanteId()
              : (ctx.cnesSolicitante() != null ? ctx.cnesSolicitante() : "");
      if (mapping.bundle().identifierSystem() != null) {
        bundle.setIdentifier(
            new Identifier()
                .setSystem(
                    mapping.bundle().identifierSystem().replace("{solicitante}", solicitante))
                .setValue(identifierValue));
      }
      bundle.setTimestamp(Date.from(ctx.now()));
      boolean document = "document".equals(ctx.bundleType());
      if (document) {
        Composition composition = composition(mainUrn, patient, authorCnes, docDate, encounterUrn);
        addEntry(composition, urn("Composition/" + src.stableId()));
      }
      for (Resource r : entries) {
        String literal = r.fhirType() + "/" + r.getIdElement().getIdPart();
        String fullUrl = urnByLiteral.get(literal);
        if (fullUrl == null) fullUrl = urn(literal);
        addEntry(r, fullUrl);
      }
      // Referências literais restantes: reescreve para urn:uuid ou referência lógica
      for (Bundle.BundleEntryComponent e : bundle.getEntry()) {
        e.getResource().setId((String) null);
        rewriteReferences(e.getResource(), null, null);
      }
      if (!document) {
        for (Bundle.BundleEntryComponent e : bundle.getEntry()) {
          e.getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl(e.getResource().fhirType());
        }
      }
      return new AssembledBundle(
          mapping.model(), mapping.version(), src.stableId(), bundle, facts, warnings);
    }

    private Composition composition(
        String mainUrn, Reference patient, String authorCnes, Date date, String encounterUrn) {
      ModelMapping.CompositionSpec spec = mapping.composition();
      Composition c = new Composition();
      if (spec != null && spec.profile() != null) c.getMeta().addProfile(spec.profile());
      c.setStatus(Composition.CompositionStatus.FINAL);
      if (spec != null && spec.type() != null) c.setType(concept(spec.type()));
      if (spec != null && spec.category() != null) c.addCategory(concept(spec.category()));
      c.setSubject(patient.copy());
      if (encounterUrn != null) c.setEncounter(new Reference(encounterUrn));
      c.setDate(date);
      if (authorCnes != null) c.addAuthor(cnesReference(authorCnes));
      c.setTitle(spec != null && spec.title() != null ? spec.title() : mapping.model());
      Composition.SectionComponent section = c.addSection();
      if (spec != null && spec.sectionTitle() != null) section.setTitle(spec.sectionTitle());
      section.addEntry(new Reference(mainUrn));
      return c;
    }

    private static CodeableConcept concept(ModelMapping.CodingSpec spec) {
      return new CodeableConcept()
          .addCoding(new Coding(spec.system(), spec.code(), spec.display()));
    }

    private void addEntry(Resource resource, String fullUrl) {
      bundle.addEntry().setFullUrl(fullUrl).setResource(resource);
    }

    /** Perfil do mapeamento, sem meta/text de origem, e remoções declaradas. */
    private void prepare(DomainResource resource, String type) {
      Meta meta = new Meta();
      String profile = mapping.resource(type).profile();
      if (profile != null) meta.addProfile(profile);
      resource.setMeta(meta);
      resource.setText(null);
      resource.getContained().clear();
      for (String element : mapping.strip().elements()) {
        int dot = element.indexOf('.');
        if (dot < 0 || !element.substring(0, dot).equals(type)) continue;
        String name = element.substring(dot + 1);
        for (Base child : new ArrayList<>(resource.listChildrenByName(name))) {
          resource.removeChild(name, child);
        }
      }
      stripLocal(resource);
      for (Property p : resource.children()) {
        if (!"identifier".equals(p.getName())) continue;
        for (Base v : new ArrayList<>(p.getValues())) {
          if (v instanceof Identifier id && isLocal(id.getSystem())) {
            resource.removeChild("identifier", v);
          }
        }
      }
    }

    /** Remove extensões locais em toda a árvore. */
    private void stripLocal(Base base) {
      if (base instanceof DomainResource dr) {
        dr.getExtension()
            .removeIf(e -> startsWithAny(e.getUrl(), mapping.strip().extensionPrefixes()));
        dr.getModifierExtension()
            .removeIf(e -> startsWithAny(e.getUrl(), mapping.strip().extensionPrefixes()));
      } else if (base instanceof Element el) {
        el.getExtension()
            .removeIf(e -> startsWithAny(e.getUrl(), mapping.strip().extensionPrefixes()));
      }
      for (Property p : base.children()) {
        for (Base child : p.getValues()) {
          if (child != null && !child.isPrimitive()) stripLocal(child);
        }
      }
    }

    private void rewriteReferences(Base base, Base parent, String propertyName) {
      if (base instanceof Reference ref) {
        String literal = ref.getReference();
        if (literal != null && !literal.startsWith("urn:")) {
          String key = normalize(literal);
          if (urnByLiteral.containsKey(key)) {
            ref.setReference(urnByLiteral.get(key));
          } else if (logicalByLiteral.containsKey(key)) {
            Reference logical = logicalByLiteral.get(key);
            ref.setReference(null);
            ref.setType(logical.getType());
            ref.setIdentifier(logical.getIdentifier().copy());
          } else if (ref.hasIdentifier() && !isLocal(ref.getIdentifier().getSystem())) {
            ref.setReference(null);
          } else if (parent != null) {
            parent.removeChild(propertyName, ref);
            warnings.add("referência não resolvida removida: " + key.replaceAll("/.*", ""));
          }
        }
        if (ref.hasIdentifier() && isLocal(ref.getIdentifier().getSystem())) {
          if (ref.hasReference()) {
            ref.setIdentifier(null);
          } else if (parent != null) {
            parent.removeChild(propertyName, ref);
          }
        }
        return;
      }
      for (Property p : base.children()) {
        for (Base child : new ArrayList<>(p.getValues())) {
          if (child != null && !child.isPrimitive()) rewriteReferences(child, base, p.getName());
        }
      }
    }

    private static String normalize(String literal) {
      String[] parts = literal.split("/");
      if (parts.length >= 2) {
        // ignora base absoluta e _history
        for (int i = parts.length - 1; i >= 1; i--) {
          if (Character.isUpperCase(parts[i - 1].isEmpty() ? 'x' : parts[i - 1].charAt(0))
              && !"_history".equals(parts[i])) {
            return parts[i - 1] + "/" + parts[i];
          }
        }
      }
      return literal;
    }

    private boolean isLocal(String system) {
      return system != null && startsWithAny(system, mapping.strip().identifierPrefixes());
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
      if (value == null) return false;
      for (String p : prefixes) if (value.startsWith(p)) return true;
      return false;
    }

    private String urn(String literal) {
      return urnByLiteral.computeIfAbsent(
          literal,
          l ->
              "urn:uuid:"
                  + UUID.nameUUIDFromBytes(
                      (ctx.eventId() + ":" + l).getBytes(StandardCharsets.UTF_8)));
    }

    private Reference patientReference() {
      Patient p = src.patient();
      if (p == null) throw new IllegalArgumentException("Patient ausente");
      String cns = identifierValue(p.getIdentifier(), mapping.identifierSystems().cns());
      String cpf = identifierValue(p.getIdentifier(), mapping.identifierSystems().cpf());
      fact("patient.cns", cns);
      fact("patient.cpf", cpf);
      Reference ref = new Reference().setType("Patient");
      if (cns != null && Documents.validCns(cns)) {
        ref.setIdentifier(
            new Identifier()
                .setSystem(mapping.identifierSystems().cns())
                .setValue(Documents.digits(cns)));
      } else if (cpf != null && Documents.validCpf(cpf)) {
        ref.setIdentifier(
            new Identifier()
                .setSystem(mapping.identifierSystems().cpf())
                .setValue(Documents.digits(cpf)));
      }
      logicalByLiteral.put("Patient/" + p.getIdElement().getIdPart(), ref);
      return ref;
    }

    private Optional<Reference> practitionerReference(Practitioner p) {
      String cns = identifierValue(p.getIdentifier(), mapping.identifierSystems().cns());
      String cpf = identifierValue(p.getIdentifier(), mapping.identifierSystems().cpf());
      Reference ref = new Reference().setType("Practitioner");
      if (cns != null && Documents.validCns(cns)) {
        ref.setIdentifier(
            new Identifier()
                .setSystem(mapping.identifierSystems().cns())
                .setValue(Documents.digits(cns)));
      } else if (cpf != null && Documents.validCpf(cpf)) {
        ref.setIdentifier(
            new Identifier()
                .setSystem(mapping.identifierSystems().cpf())
                .setValue(Documents.digits(cpf)));
      } else {
        warnings.add("profissional sem CNS/CPF válido: omitido");
        return Optional.empty();
      }
      logicalByLiteral.put("Practitioner/" + p.getIdElement().getIdPart(), ref);
      fact("practitioner.identifier", "present");
      return Optional.of(ref);
    }

    private Reference cnesReference(String cnes) {
      return new Reference()
          .setType("Organization")
          .setIdentifier(
              new Identifier().setSystem(mapping.identifierSystems().cnes()).setValue(cnes));
    }

    /** CNES de uma referência: lógica (identifier CNES) ou literal resolvida pelo fetcher. */
    private String cnesOf(Reference ref) {
      if (ref.hasIdentifier()
          && mapping.identifierSystems().cnes().equals(ref.getIdentifier().getSystem())) {
        return ref.getIdentifier().getValue();
      }
      if (ref.hasReference()) {
        String key = normalize(ref.getReference());
        String cnes = src.organizationCnes().get(key);
        if (cnes != null) {
          logicalByLiteral.put(key, cnesReference(cnes));
          return cnes;
        }
      }
      return null;
    }

    private static String identifierValue(List<Identifier> ids, String system) {
      return ids.stream()
          .filter(i -> system.equals(i.getSystem()) && i.hasValue())
          .map(Identifier::getValue)
          .findFirst()
          .orElse(null);
    }

    /** Patient mínimo (só identificadores nacionais) quando o modelo exige o recurso. */
    private Patient minimalPatient(Patient source) {
      Patient p = new Patient();
      p.setId(source.getIdElement().getIdPart());
      for (Identifier id : source.getIdentifier()) {
        if (mapping.identifierSystems().cns().equals(id.getSystem())
            || mapping.identifierSystems().cpf().equals(id.getSystem())) {
          p.addIdentifier(id.copy());
        }
      }
      prepare(p, "Patient");
      urn("Patient/" + source.getIdElement().getIdPart());
      logicalByLiteral.remove("Patient/" + source.getIdElement().getIdPart());
      return p;
    }

    private String versioned(DomainResource source) {
      String version =
          source.hasMeta() && source.getMeta().hasVersionId()
              ? source.getMeta().getVersionId()
              : "1";
      return src.stableId() + "-v" + version;
    }

    private void fact(String name, String value) {
      if (value != null && !value.isBlank()) facts.put(name, value);
    }
  }
}
