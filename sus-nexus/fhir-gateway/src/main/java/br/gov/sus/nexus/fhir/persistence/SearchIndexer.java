package br.gov.sus.nexus.fhir.persistence;

import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import br.gov.sus.nexus.fhir.capability.SearchParamDef;
import br.gov.sus.nexus.fhir.fhirpath.FhirPathEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.BaseDateTimeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.PrimitiveType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;

/**
 * Extrai entradas de índice de um recurso a partir das expressões FHIRPath registradas no {@link
 * ResourceCapability}. Cada tipo de parâmetro sabe lidar com os tipos de dado FHIR usuais.
 */
@ApplicationScoped
public class SearchIndexer {

  @Inject FhirPathEvaluator fhirPath;

  public List<IndexEntry> index(Resource resource, ResourceCapability capability) {
    List<IndexEntry> entries = new ArrayList<>();
    for (SearchParamDef def : capability.searchParams().values()) {
      if (def.isComputed()) {
        continue;
      }
      List<Base> values = fhirPath.evaluate(resource, def.expression());
      for (Base value : values) {
        switch (def.type()) {
          case TOKEN -> indexToken(def.name(), value, entries);
          case STRING -> indexString(def.name(), value, entries);
          case DATE -> indexDate(def.name(), value, entries);
          case REFERENCE -> indexReference(def.name(), value, entries);
        }
      }
    }
    return entries;
  }

  private static void indexToken(String param, Base value, List<IndexEntry> out) {
    if (value instanceof Identifier id) {
      if (id.hasValue()) {
        out.add(new IndexEntry.Token(param, id.getSystem(), id.getValue()));
      }
    } else if (value instanceof Coding c) {
      if (c.hasCode()) {
        out.add(new IndexEntry.Token(param, c.getSystem(), c.getCode()));
      }
    } else if (value instanceof CodeableConcept cc) {
      for (Coding c : cc.getCoding()) {
        if (c.hasCode()) {
          out.add(new IndexEntry.Token(param, c.getSystem(), c.getCode()));
        }
      }
    } else if (value instanceof ContactPoint cp) {
      if (cp.hasValue()) {
        out.add(
            new IndexEntry.Token(
                param, cp.hasSystem() ? cp.getSystem().toCode() : null, cp.getValue()));
      }
    } else if (value instanceof PrimitiveType<?> p && p.hasValue()) {
      out.add(new IndexEntry.Token(param, null, p.primitiveValue()));
    }
  }

  private static void indexString(String param, Base value, List<IndexEntry> out) {
    if (value instanceof HumanName name) {
      addString(param, name.getText(), out);
      addString(param, name.getFamily(), out);
      for (StringType s : name.getGiven()) {
        addString(param, s.getValue(), out);
      }
      for (StringType s : name.getPrefix()) {
        addString(param, s.getValue(), out);
      }
      for (StringType s : name.getSuffix()) {
        addString(param, s.getValue(), out);
      }
    } else if (value instanceof PrimitiveType<?> p && p.hasValue()) {
      addString(param, p.primitiveValue(), out);
    }
  }

  private static void addString(String param, String raw, List<IndexEntry> out) {
    if (raw == null || raw.isBlank()) {
      return;
    }
    out.add(new IndexEntry.Str(param, StringNormalizer.normalize(raw)));
  }

  private static void indexDate(String param, Base value, List<IndexEntry> out) {
    if (value instanceof Period period) {
      Optional<FhirDates.Range> low =
          period.hasStart()
              ? FhirDates.parse(period.getStartElement().getValueAsString())
              : Optional.empty();
      Optional<FhirDates.Range> high =
          period.hasEnd()
              ? FhirDates.parse(period.getEndElement().getValueAsString())
              : Optional.empty();
      out.add(
          new IndexEntry.Date(
              param,
              low.map(FhirDates.Range::low).orElse(FhirDates.MIN),
              high.map(FhirDates.Range::high).orElse(FhirDates.MAX)));
    } else if (value instanceof BaseDateTimeType dt && dt.hasValue()) {
      FhirDates.parse(dt.getValueAsString())
          .ifPresent(r -> out.add(new IndexEntry.Date(param, r.low(), r.high())));
    }
  }

  private static void indexReference(String param, Base value, List<IndexEntry> out) {
    if (!(value instanceof Reference ref)) {
      return;
    }
    if (ref.hasReference()) {
      parseReference(ref.getReference())
          .ifPresent(t -> out.add(new IndexEntry.Ref(param, t.type(), t.id())));
    } else if (ref.hasIdentifier() && ref.getIdentifier().hasValue()) {
      // referência lógica (somente identifier): indexada pelo valor, com o tipo declarado se houver
      out.add(
          new IndexEntry.Ref(
              param, ref.hasType() ? ref.getType() : null, ref.getIdentifier().getValue()));
    }
  }

  /** Alvo de referência {@code Type/id}. */
  public record Target(String type, String id) {}

  /** Interpreta referências relativas ou absolutas ({@code [base/]Type/id[/_history/v]}). */
  public static Optional<Target> parseReference(String reference) {
    if (reference == null || reference.isBlank() || reference.startsWith("#")) {
      return Optional.empty();
    }
    String r = reference;
    int h = r.indexOf("/_history/");
    if (h > 0) {
      r = r.substring(0, h);
    }
    String[] parts = r.split("/");
    if (parts.length < 2) {
      return Optional.empty();
    }
    String id = parts[parts.length - 1];
    String type = parts[parts.length - 2];
    if (!type.matches("[A-Z][A-Za-z]+") || !id.matches("[A-Za-z0-9\\-\\.]{1,64}")) {
      return Optional.empty();
    }
    return Optional.of(new Target(type, id));
  }
}
