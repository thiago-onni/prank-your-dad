package br.gov.sus.nexus.connectors.rnds;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Element;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Specimen;

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

    /** Referências literais externas intencionais (ex.: relatesTo → documento já na RNDS). */
    private final Set<String> keepLiteral = new HashSet<>();

    private final Bundle bundle = new Bundle();

    Run(ModelMapping mapping, SourceResources src, Context ctx) {
      this.mapping = mapping;
      this.src = src;
      this.ctx = ctx;
    }

    // ------------------------------------------------------------------ resultado de exame

    /**
     * REL conforme o Modelo Computacional da RNDS: {@code Composition}
     * (BRResultadoExameLaboratorial) → {@code Observation} (BRDiagnosticoLaboratorioClinico, uma
     * por resultado) → {@code Specimen} (BRAmostraBiologica). As Observations são construídas só
     * com os elementos que o perfil permite; o DiagnosticReport do gateway é apenas fonte (status,
     * emissão, executante, código SIGTAP).
     */
    AssembledBundle examResult() {
      DiagnosticReport report = src.report();
      if (report == null) throw new IllegalArgumentException("DiagnosticReport ausente");
      ModelMapping.ObservationSpec spec = mapping.observation();
      if (spec == null) {
        throw new IllegalArgumentException("mapeamento " + mapping.model() + " sem 'observation'");
      }
      Reference patient = patientReference();
      fact("report.status", report.getStatus() == null ? null : report.getStatus().toCode());
      if (report.hasEffectiveDateTimeType()) {
        fact("exam.date", report.getEffectiveDateTimeType().getValueAsString());
      }
      Date issued = report.hasIssued() ? report.getIssued() : null;
      if (issued != null) fact("exam.issued", report.getIssuedElement().getValueAsString());

      // Categoria = subgrupo da Tabela SUS (4 primeiros dígitos do código SIGTAP do exame)
      String sigtap = codeIn(report.getCode(), spec.categoryFromSystem());
      if (sigtap == null && src.serviceRequest() != null) {
        sigtap = codeIn(src.serviceRequest().getCode(), spec.categoryFromSystem());
      }
      String category =
          sigtap != null && sigtap.length() >= 4 ? sigtap.substring(0, 4) : spec.defaultCategory();

      // Laboratório executante (CNES): referência lógica
      String performerCnes = null;
      for (Reference ref : report.getPerformer()) {
        String cnes = cnesOf(ref);
        if (cnes != null) {
          performerCnes = cnes;
          break;
        }
      }
      if (performerCnes == null) performerCnes = src.eventData().get("performer_cnes");
      fact("performer.cnes", performerCnes);

      List<Resource> entries = new ArrayList<>();
      List<String> sectionUrns = new ArrayList<>();
      Map<String, String> specimenUrns = new HashMap<>();
      boolean allCode = true;
      boolean allValue = true;
      boolean allMethod = true;
      boolean allRange = true;
      boolean allSpecimen = true;
      for (Observation o : src.observations()) {
        Observation out = new Observation();
        String id = o.getIdElement().getIdPart();
        out.setId(id);
        sectionUrns.add(urn("Observation/" + id));
        out.setStatus(
            Observation.ObservationStatus.fromCode(
                spec.status() == null ? "final" : spec.status()));
        if (category != null) {
          out.addCategory().addCoding().setSystem(spec.categorySystem()).setCode(category);
        }
        Coding code = mapExamCode(o.getCode(), spec);
        if (code == null) code = mapExamCode(report.getCode(), spec);
        if (code != null) {
          out.getCode().addCoding(code);
          if (!facts.containsKey("exam.code")) {
            fact("exam.code", code.getCode());
            fact("exam.code_system", code.getSystem());
          } else if (!code.getSystem().equals(facts.get("exam.code_system"))) {
            facts.put("exam.code_system", code.getSystem()); // um sistema fora da lista reprova
          }
        } else {
          allCode = false;
        }
        out.setSubject(patient.copy());
        if (o.hasEffective()) {
          out.setEffective(o.getEffective().copy());
        } else if (report.hasEffective()) {
          out.setEffective(report.getEffective().copy());
        }
        out.setIssued(o.hasIssued() ? o.getIssued() : issued);
        String obsCnes = null;
        for (Reference ref : o.getPerformer()) {
          obsCnes = cnesOf(ref);
          if (obsCnes != null) break;
        }
        String cnes = obsCnes != null ? obsCnes : performerCnes;
        if (cnes != null) out.addPerformer(cnesReference(cnes));
        allValue &= copyValue(o, out, spec);
        o.getNote().stream()
            .filter(n -> n.hasText())
            .forEach(n -> out.addNote().setText(n.getText()));
        String method = o.hasMethod() ? text(o.getMethod()) : null;
        if (method != null) {
          out.getMethod().setText(method);
        } else {
          allMethod = false;
        }
        String range = referenceRangeText(o);
        if (range != null) {
          out.addReferenceRange().setText(range);
        } else {
          allRange = false;
        }
        Specimen specimen = sourceSpecimen(o, report);
        Coding type = specimen == null ? null : specimenType(specimen, spec);
        if (type != null) {
          String key = "Specimen/" + specimen.getIdElement().getIdPart();
          String specimenUrn = specimenUrns.get(key);
          if (specimenUrn == null) {
            Specimen sp = new Specimen();
            sp.setId(specimen.getIdElement().getIdPart());
            sp.getType().addCoding(type);
            prepare(sp, "Specimen");
            specimenUrn = urn(key);
            specimenUrns.put(key, specimenUrn);
            entries.add(out);
            entries.add(sp);
          } else {
            entries.add(out);
          }
          out.setSpecimen(new Reference(specimenUrn));
        } else {
          allSpecimen = false;
          entries.add(out);
        }
        prepare(out, "Observation");
      }
      int count = src.observations().size();
      fact("observations.count", String.valueOf(count));
      if (count > 0) {
        if (!allCode) facts.remove("exam.code");
        if (category != null) fact("observation.category", category);
        if (allValue) fact("observation.value", "present");
        if (allMethod) fact("observation.method", "present");
        if (allRange) fact("observation.reference_range", "present");
        if (allSpecimen) fact("observation.specimen_type", "present");
      }
      String authorCnes = ctx.cnesSolicitante() != null ? ctx.cnesSolicitante() : performerCnes;
      Date docDate = issued != null ? issued : Date.from(ctx.now());
      return finish(entries, sectionUrns, patient, authorCnes, docDate, null, versioned(report));
    }

    /** Código do exame no sistema de destino ({@code observation.code_system_map}), sem display. */
    private static Coding mapExamCode(CodeableConcept cc, ModelMapping.ObservationSpec spec) {
      if (cc == null) return null;
      for (Coding c : cc.getCoding()) {
        String target = c.getSystem() == null ? null : spec.codeSystemMap().get(c.getSystem());
        if (target != null && c.hasCode())
          return new Coding().setSystem(target).setCode(c.getCode());
      }
      return null;
    }

    private static String codeIn(CodeableConcept cc, String system) {
      if (cc == null || system == null) return null;
      return cc.getCoding().stream()
          .filter(c -> system.equals(c.getSystem()) && c.hasCode())
          .map(Coding::getCode)
          .findFirst()
          .orElse(null);
    }

    /**
     * Resultado: {@code valueQuantity} (+ interpretação qualitativa, se codificada no sistema
     * oficial) ou {@code valueCodeableConcept} no CodeSystem BRResultadoQualitativoExame.
     */
    private static boolean copyValue(
        Observation from, Observation to, ModelMapping.ObservationSpec spec) {
      if (from.hasValueQuantity()) {
        // Só valor/unidade (como no exemplo oficial): BRDiagnosticoLaboratorioClinico-1.0 vincula
        // value[x] ao ValueSet BRResultadoQualitativoExame (required), o que reprova system/code
        // UCUM.
        Quantity q = from.getValueQuantity();
        Quantity out = new Quantity();
        if (q.hasValue()) out.setValue(q.getValue());
        if (q.hasComparator()) out.setComparator(q.getComparator());
        if (q.hasUnit()) {
          out.setUnit(q.getUnit());
        } else if (q.hasCode()) {
          out.setUnit(q.getCode());
        }
        to.setValue(out);
        for (CodeableConcept i : from.getInterpretation()) {
          Coding c = qualitative(i, spec);
          if (c != null) {
            to.addInterpretation().addCoding(c);
            break;
          }
        }
        return true;
      }
      if (from.hasValueCodeableConcept()) {
        Coding c = qualitative(from.getValueCodeableConcept(), spec);
        if (c != null) {
          to.setValue(new CodeableConcept().addCoding(c));
          return true;
        }
      }
      return false;
    }

    private static Coding qualitative(CodeableConcept cc, ModelMapping.ObservationSpec spec) {
      for (Coding c : cc.getCoding()) {
        if (spec.qualitativeSystem() != null
            && spec.qualitativeSystem().equals(c.getSystem())
            && c.hasCode()) {
          return new Coding().setSystem(c.getSystem()).setCode(c.getCode());
        }
      }
      return null;
    }

    private static String text(CodeableConcept cc) {
      if (cc.hasText()) return cc.getText();
      return cc.getCoding().stream()
          .filter(Coding::hasDisplay)
          .map(Coding::getDisplay)
          .findFirst()
          .orElse(null);
    }

    /** Faixa de referência como texto (o perfil só permite {@code referenceRange.text}). */
    private static String referenceRangeText(Observation o) {
      if (!o.hasReferenceRange()) return null;
      Observation.ObservationReferenceRangeComponent rr = o.getReferenceRangeFirstRep();
      if (rr.hasText()) return rr.getText();
      String low =
          rr.hasLow() && rr.getLow().hasValue() ? rr.getLow().getValue().toPlainString() : null;
      String high =
          rr.hasHigh() && rr.getHigh().hasValue() ? rr.getHigh().getValue().toPlainString() : null;
      if (low == null && high == null) return null;
      String unit =
          rr.hasLow() && rr.getLow().hasUnit()
              ? rr.getLow().getUnit()
              : rr.hasHigh() && rr.getHigh().hasUnit() ? rr.getHigh().getUnit() : null;
      String text =
          low != null && high != null
              ? low + " a " + high
              : low != null ? ">= " + low : "<= " + high;
      return unit == null ? text : text + " " + unit;
    }

    private Specimen sourceSpecimen(Observation o, DiagnosticReport report) {
      if (o.hasSpecimen() && o.getSpecimen().hasReference()) {
        Specimen s = src.specimens().get(normalize(o.getSpecimen().getReference()));
        if (s != null) return s;
      }
      for (Reference ref : report.getSpecimen()) {
        if (ref.hasReference()) {
          Specimen s = src.specimens().get(normalize(ref.getReference()));
          if (s != null) return s;
        }
      }
      return null;
    }

    private static Coding specimenType(Specimen s, ModelMapping.ObservationSpec spec) {
      for (Coding c : s.getType().getCoding()) {
        if (c.getSystem() != null
            && spec.specimenTypeSystems().contains(c.getSystem())
            && c.hasCode()) {
          return new Coding().setSystem(c.getSystem()).setCode(c.getCode());
        }
      }
      return null;
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
          entries,
          List.of(encounterUrn),
          patient,
          authorCnes,
          docDate,
          encounterUrn,
          versioned(source));
    }

    // ------------------------------------------------------------------ comum

    private AssembledBundle finish(
        List<Resource> entries,
        List<String> sectionUrns,
        Reference patient,
        String authorCnes,
        Date docDate,
        String encounterUrn,
        String versionedIdentifier) {
      bundle.setType(Bundle.BundleType.fromCode(ctx.bundleType()));
      if (mapping.bundle().profile() != null)
        bundle.getMeta().addProfile(mapping.bundle().profile());
      // Identificador do solicitante: atribuído pela RNDS na aprovação do acesso (NÃO é o CNES)
      String solicitante = ctx.solicitanteId() == null ? "" : ctx.solicitanteId().trim();
      fact("requester.solicitante_id", solicitante);
      if (mapping.bundle().identifierSystem() != null) {
        String value =
            "source_id_version".equals(mapping.bundle().identifierValueOrDefault())
                ? versionedIdentifier
                : src.stableId();
        bundle.setIdentifier(
            new Identifier()
                .setSystem(
                    mapping.bundle().identifierSystem().replace("{solicitante}", solicitante))
                .setValue(value));
      }
      bundle.setTimestamp(Date.from(ctx.now()));
      boolean document = "document".equals(ctx.bundleType());
      if (document) {
        Composition composition =
            composition(sectionUrns, patient, authorCnes, docDate, encounterUrn);
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
        List<String> sectionUrns,
        Reference patient,
        String authorCnes,
        Date date,
        String encounterUrn) {
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
      // Substituição de documento aceito: relatesTo replaces Composition/<id atribuído pela RNDS>
      if (src.replacesProtocol() != null && mapping.replacement() != null) {
        String target =
            mapping.replacement().targetReference().replace("{protocolo}", src.replacesProtocol());
        keepLiteral.add(target);
        c.addRelatesTo()
            .setCode(Composition.DocumentRelationshipType.fromCode(mapping.replacement().code()))
            .setTarget(new Reference(target));
        fact("replaces.protocol", src.replacesProtocol());
      }
      Composition.SectionComponent section = c.addSection();
      if (spec != null && spec.sectionTitle() != null) section.setTitle(spec.sectionTitle());
      for (String urn : sectionUrns) section.addEntry(new Reference(urn));
      return c;
    }

    private static CodeableConcept concept(ModelMapping.CodingSpec spec) {
      Coding coding = new Coding().setSystem(spec.system()).setCode(spec.code());
      if (spec.display() != null) coding.setDisplay(spec.display());
      return new CodeableConcept().addCoding(coding);
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
          try {
            resource.removeChild(name, child);
          } catch (FHIRException e) {
            resource.removeChild(name + "[x]", child); // elemento de escolha (ex.: effective[x])
          }
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
        if (literal != null && !literal.startsWith("urn:") && !keepLiteral.contains(literal)) {
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
      Reference ref = new Reference();
      if (mapping.targetSystems() == null) ref.setType("Patient");
      if (cns != null && Documents.validCns(cns) && mapping.targetPatientCns() != null) {
        ref.setIdentifier(
            new Identifier().setSystem(mapping.targetPatientCns()).setValue(Documents.digits(cns)));
      } else if (cpf != null && Documents.validCpf(cpf) && mapping.targetPatientCpf() != null) {
        ref.setIdentifier(
            new Identifier().setSystem(mapping.targetPatientCpf()).setValue(Documents.digits(cpf)));
      }
      logicalByLiteral.put("Patient/" + p.getIdElement().getIdPart(), ref);
      return ref;
    }

    /**
     * Estabelecimento por CNES no sistema de destino. Com {@code target_systems} (REL) o perfil
     * proíbe {@code Reference.type} (0..0); sem ele mantém-se {@code type=Organization}.
     */
    private Reference cnesReference(String cnes) {
      Reference ref =
          new Reference()
              .setIdentifier(new Identifier().setSystem(mapping.targetCnes()).setValue(cnes));
      if (mapping.targetSystems() == null) ref.setType("Organization");
      return ref;
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
